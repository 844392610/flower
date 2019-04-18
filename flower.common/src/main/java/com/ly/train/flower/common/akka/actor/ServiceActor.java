/**
 * Copyright © 2019 同程艺龙 (zhihui.li@ly.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ly.train.flower.common.akka.actor;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import com.ly.train.flower.common.akka.ServiceRouter;
import com.ly.train.flower.common.akka.serializer.protostuff.ProtobufUtils;
import com.ly.train.flower.common.exception.ServiceException;
import com.ly.train.flower.common.service.Aggregate;
import com.ly.train.flower.common.service.Complete;
import com.ly.train.flower.common.service.FlowerService;
import com.ly.train.flower.common.service.Service;
import com.ly.train.flower.common.service.config.ServiceConfig;
import com.ly.train.flower.common.service.container.FlowerFactory;
import com.ly.train.flower.common.service.container.ServiceContext;
import com.ly.train.flower.common.service.container.ServiceMeta;
import com.ly.train.flower.common.service.container.util.ServiceContextUtil;
import com.ly.train.flower.common.service.impl.AggregateService;
import com.ly.train.flower.common.service.message.Condition;
import com.ly.train.flower.common.service.message.FlowMessage;
import com.ly.train.flower.common.service.web.Flush;
import com.ly.train.flower.common.service.web.HttpComplete;
import com.ly.train.flower.common.service.web.Web;
import com.ly.train.flower.common.util.ClassUtil;
import com.ly.train.flower.common.util.CloneUtil;
import com.ly.train.flower.common.util.CollectionUtil;
import com.ly.train.flower.common.util.ExceptionUtil;
import com.ly.train.flower.common.util.StringUtil;
import com.ly.train.flower.common.util.cache.Cache;
import com.ly.train.flower.common.util.cache.CacheManager;
import akka.actor.ActorRef;
import akka.actor.Props;

/**
 * Wrap service by actor, make service driven by message.
 * 
 * @author zhihui.li
 *
 */
public class ServiceActor extends AbstractFlowerActor {

  public static final Long defaultTimeToLive = TimeUnit.SECONDS.toMillis(60);
  private static final String serviceActorCachePrefix = "FLOWER_SERVICE_ACTOR_";
  private static final ConcurrentMap<String, Set<RefType>> nextServiceActorCache = new ConcurrentHashMap<>();
  private FlowerService service;
  private Class<?> paramType;
  private int count;
  private final FlowerFactory flowerFactory;

  static public Props props(String serviceName, FlowerFactory flowerFactory, int count) {
    return Props.create(ServiceActor.class, serviceName, flowerFactory, count);
  }

  /**
   * 当前Actor绑定的服务
   */
  private String serviceName;

  public ServiceActor(String serviceName, FlowerFactory flowerFactory, int count) {
    this.serviceName = serviceName;
    this.count = count;
    this.flowerFactory = flowerFactory;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Override
  public void onServiceContextReceived(ServiceContext serviceContext) throws Throwable {
    FlowMessage<?> flowMessage = serviceContext.getFlowMessage();
    if (serviceContext.isSync()) {
      CacheManager.get(serviceActorCachePrefix + serviceContext.getFlowName()).add(serviceContext.getId(), getSender(),
          defaultTimeToLive);
    }

    FlowMessage<?> resultMessage = new FlowMessage<>();
    try {
      ServiceContextUtil.fillServiceContext(serviceContext);
      byte[] paramByte = ProtobufUtils.encode(flowMessage.getMessage());
      Object param = ProtobufUtils.decode(paramByte, getParamType());
      logger.info("服务参数类型 {} : {}", getParamType(), getService(serviceContext));
      Object result = ((Service) getService(serviceContext)).process(param, serviceContext);
      resultMessage.setMessage(result);
    } catch (Throwable e) {
      Web web = serviceContext.getWeb();
      if (web != null) {
        web.complete();
      }

      Exception e2 = new ServiceException("invoke service " + serviceContext.getCurrentServiceName() + " : " + service
          + "\r\n, param : " + flowMessage.getMessage(), e);
      resultMessage.setException(ExceptionUtil.getErrorMessage(e2));
      if (serviceContext.isSync()) {
        handleSyncResult(serviceContext, resultMessage);
      }
      throw e2;
    }

    Set<RefType> nextActorRef = getNextServiceActors(serviceContext);
    if (serviceContext.isSync() && CollectionUtil.isEmpty(nextActorRef)) {
      handleSyncResult(serviceContext, resultMessage);
      return;
    }

    Web web = serviceContext.getWeb();
    if (web != null) {
      if (service instanceof Flush) {
        web.flush();
      }
      if (service instanceof HttpComplete || service instanceof Complete) {
        web.complete();
      }
    }

    handleNextServices(serviceContext, resultMessage);
  }

  /**
   * 处理同步消息
   * 
   * @param serviceContext 上下文 {@link ServiceContext}
   * @param resultMessage 消息内容 {@link FlowMessage}
   */
  private void handleSyncResult(ServiceContext serviceContext, FlowMessage<?> resultMessage) {
    CacheManager cacheManager = CacheManager.get(serviceActorCachePrefix + serviceContext.getFlowName());
    Cache<ActorRef> cache = cacheManager.getCache(serviceContext.getId());
    if (cache == null) {
      logger.warn("maybe it's timeout. serviceContext : {}", serviceContext);
      return;
    }
    ActorRef actor = cache.getValue();
    if (actor != null) {
      actor.tell(resultMessage, getSelf());
      cacheManager.invalidate(serviceContext.getId());
    }
  }

  /**
   * 处理当前服务的下行服务节点
   * 
   * @param serviceContext 上下文 {@link ServiceContext}
   * @param resultMessage 消息内容 {@link FlowMessage}
   */
  private void handleNextServices(ServiceContext serviceContext, FlowMessage<?> resultMessage) {
    if (resultMessage.getMessage() == null) {// for joint service
      return;
    }
    Set<RefType> refTypes = getNextServiceActors(serviceContext);
    if (refTypes == null) {
      return;
    }
    ServiceContextUtil.cleanServiceContext(serviceContext);
    Object result = resultMessage.getMessage();
    final String transactionId = StringUtil.uuid();// 聚合消息时使用一致的id
    for (RefType refType : refTypes) {
      // condition fork for one-service to multi-service
      if (refType.getMessageType().isInstance(result)) {
        if (!(result instanceof Condition) || !(((Condition) result).getCondition() instanceof String)
            || StringUtil.stringInStrings(refType.getServiceName(), ((Condition) result).getCondition().toString())) {
          FlowMessage<?> resultMessageClone = CloneUtil.clone(resultMessage);
          if (refType.isAggregate()) {
            resultMessageClone.setTransactionId(transactionId);
          }
          ServiceContext context = serviceContext.newInstance();
          context.setFlowMessage(resultMessageClone);
          context.setCurrentServiceName(refType.getServiceName());
          refType.getServiceRouter().asyncCallService(context, getSelf());
        }
      }
    }
  }

  /**
   * 懒加载方式获取服务实例
   * 
   * @return {@link FlowerService}
   */
  public FlowerService getService(ServiceContext serviceContext) {
    if (this.service == null) {
      this.service = flowerFactory.getServiceFactory().getServiceLoader().loadService(serviceName);
      ServiceMeta serviceMeta = flowerFactory.getServiceFactory().getServiceLoader().loadServiceMeta(serviceName);
      this.paramType = ClassUtil.forName(serviceMeta.getParamType());
      if (service instanceof Aggregate) {
        int num = flowerFactory.getServiceFactory().getOrCreateServiceFlow(serviceContext.getFlowName())
            .getServiceConfig(serviceName).getJointSourceNumber();
        ((AggregateService) service).setSourceNumber(num);
      }
    }
    return service;
  }

  private Class<?> getParamType() {
    if (this.paramType == null) {
      this.service = flowerFactory.getServiceFactory().getServiceLoader().loadService(serviceName);
      ServiceMeta serviceMeta = flowerFactory.getServiceFactory().getServiceLoader().loadServiceMeta(serviceName);
      this.paramType = ClassUtil.forName(serviceMeta.getParamType());
    }
    return paramType;
  }

  private Set<RefType> getNextServiceActors(ServiceContext serviceContext) {
    final String cacheKey = serviceContext.getFlowName() + "_" + serviceContext.getCurrentServiceName();
    Set<RefType> nextServiceActors = nextServiceActorCache.get(cacheKey);
    if (nextServiceActors == null && StringUtil.isNotBlank(serviceContext.getFlowName())) {
      nextServiceActors = new HashSet<>();
      Set<ServiceConfig> serviceConfigs = flowerFactory.getServiceFactory()
          .getOrCreateServiceFlow(serviceContext.getFlowName()).getNextFlow(serviceContext.getCurrentServiceName());
      if (serviceConfigs != null) {
        for (ServiceConfig serviceConfig : serviceConfigs) {
          flowerFactory.getServiceFactory().loadServiceMeta(serviceConfig);// 内部对serviceConfig的数据进行填充
          RefType refType = new RefType();
          refType.setAggregate(serviceConfig.isAggregateService());
          refType.setServiceRouter(flowerFactory.getServiceActorFactory().buildServiceRouter(serviceConfig, count));
          refType.setMessageType(ClassUtil.forName(serviceConfig.getServiceMeta().getParamType()));
          refType.setServiceName(serviceConfig.getServiceName());
          nextServiceActors.add(refType);
        }
        nextServiceActorCache.put(cacheKey, nextServiceActors);
      }
    }

    return nextServiceActors;
  }



  static class RefType {
    private ServiceRouter serviceRouter;
    private Class<?> messageType;
    private String serviceName;
    private boolean aggregate;

    public void setServiceRouter(ServiceRouter serviceRouter) {
      this.serviceRouter = serviceRouter;
    }

    public ServiceRouter getServiceRouter() {
      return serviceRouter;
    }

    public boolean isAggregate() {
      return aggregate;
    }

    public void setAggregate(boolean aggregate) {
      this.aggregate = aggregate;
    }

    public Class<?> getMessageType() {
      return messageType;
    }

    public void setMessageType(Class<?> messageType) {
      this.messageType = messageType;
    }

    public String getServiceName() {
      return serviceName;
    }

    public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("RefType [serviceRouter=");
      builder.append(serviceRouter);
      builder.append(", messageType=");
      builder.append(messageType);
      builder.append(", serviceName=");
      builder.append(serviceName);
      builder.append(", aggregate=");
      builder.append(aggregate);
      builder.append("]");
      return builder.toString();
    }

  }

}
