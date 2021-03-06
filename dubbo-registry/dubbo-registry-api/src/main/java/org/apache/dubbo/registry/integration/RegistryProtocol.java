/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.support.ProviderConsumerRegTable;
import org.apache.dubbo.registry.support.ProviderInvokerWrapper;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.Constants.ACCEPT_FOREIGN_IP;
import static org.apache.dubbo.common.Constants.ANY_VALUE;
import static org.apache.dubbo.common.Constants.BIND_IP_KEY;
import static org.apache.dubbo.common.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.common.Constants.CATEGORY_KEY;
import static org.apache.dubbo.common.Constants.CHECK_KEY;
import static org.apache.dubbo.common.Constants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.Constants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.common.Constants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.common.Constants.DEFAULT_DIRECTORY;
import static org.apache.dubbo.common.Constants.DEFAULT_REGISTER_CONSUMER_KEYS;
import static org.apache.dubbo.common.Constants.DEFAULT_REGISTER_PROVIDER_KEYS;
import static org.apache.dubbo.common.Constants.DEFAULT_REGISTRY;
import static org.apache.dubbo.common.Constants.EXPORT_KEY;
import static org.apache.dubbo.common.Constants.EXTRA_CONSUMER_CONFIG_KEYS_KEY;
import static org.apache.dubbo.common.Constants.EXTRA_PROVIDER_CONFIG_KEYS_KEY;
import static org.apache.dubbo.common.Constants.HIDE_KEY_PREFIX;
import static org.apache.dubbo.common.Constants.INTERFACES;
import static org.apache.dubbo.common.Constants.METHODS_KEY;
import static org.apache.dubbo.common.Constants.MONITOR_KEY;
import static org.apache.dubbo.common.Constants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.Constants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.Constants.PROVIDER_PROTOCOL;
import static org.apache.dubbo.common.Constants.QOS_ENABLE;
import static org.apache.dubbo.common.Constants.QOS_PORT;
import static org.apache.dubbo.common.Constants.REFER_KEY;
import static org.apache.dubbo.common.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.common.Constants.REGISTER_KEY;
import static org.apache.dubbo.common.Constants.REGISTRY_KEY;
import static org.apache.dubbo.common.Constants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.Constants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.Constants.SIMPLE_CONSUMER_CONFIG_KEY;
import static org.apache.dubbo.common.Constants.SIMPLE_PROVIDER_CONFIG_KEY;
import static org.apache.dubbo.common.Constants.VALIDATION_KEY;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;

/**
 * RegistryProtocol
 */
public class RegistryProtocol implements Protocol {

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    
    //单例。在 Dubbo SPI 中，被初始化，有且仅有一次。
    private static RegistryProtocol INSTANCE;
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<>();
    private final Map<String, ServiceConfigurationListener> serviceConfigurationListeners = new ConcurrentHashMap<>();
    private final ProviderConfigurationListener providerConfigurationListener = new ProviderConfigurationListener();
    //To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    //providerurl <--> exporter
    //用于解决rmi重复暴露端口冲突的问题，已经暴露过的服务不再重新暴露
    private final Map<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<>();
    
    //Cluster 自适应拓展实现类，也是通过 Dubbo SPI 自动注入的，在ExtensionLoader.createExtension()方法中会调用injectExtension()方法注入值
    private Cluster cluster;
    
    //Protocol 自适应拓展实现类，通过 Dubbo SPI 自动注入。
    private Protocol protocol;
    
    private RegistryFactory registryFactory;
    
    //RegistryFactory 自适应拓展实现类，通过 Dubbo SPI 自动注入。
    private ProxyFactory proxyFactory;

    public RegistryProtocol() {
        INSTANCE = this;
    }

    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (params != null && !params.isEmpty()) {
            return params.keySet().stream()
                    .filter(k -> k.startsWith(HIDE_KEY_PREFIX))
                    .toArray(String[]::new);
        } else {
            return new String[0];
        }
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    public void register(URL registryUrl, URL registeredProviderUrl) {
    	//芋道源码：http://svip.iocoder.cn/Dubbo/registry-api/?self 注册中心单独讲解
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.register(registeredProviderUrl);
    }

    public void unregister(URL registryUrl, URL registeredProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.unregister(registeredProviderUrl);
    }

    /**
     * Protocol 有两个 Wrapper 拓展实现类： ProtocolFilterWrapper、ProtocolListenerWrapper 。所以，#export(...) 方法的调用顺序是：
     * 
     * Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => RegistryProtocol 
     * => Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => DubboProtocol
     * 
     * 也就是说，这一条大的调用链，包含两条小的调用链。原因是：首先，传入的是注册中心的 URL ，通过 Protocol$Adaptive 获取到的是 RegistryProtocol 对象。
     * 其次，RegistryProtocol 会在其 #export(...) 方法中调用doLocalExport(...)方法，使用服务提供者的 URL ( 即注册中心的 URL 的 export 参数值)，再次调用 Protocol$Adaptive 获取到的是 DubboProtocol 对象，进行服务暴露。
     */
    
    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {

    	//originInvoker.getUrl()的值是ServiceConfig的642行的proxyFactory.getInvoker()的第三个入参
    	//例如：例如：registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=injvm%3A%2F%2F10.216.40.189%3A65326%2Forg.apache.dubbo.demo.DemoService%3Faccesslog%3Dtrue%26anyhost%3Dtrue%26application%3Ddemo-provider%26bind.ip%3D10.216.40.189%26bind.port%3D65326%26dubbo%3D2.0.2%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26methods%3DsayHello%26notify%3Dfalse%26pid%3D36536%26qos.port%3D22222%26side%3Dprovider%26specVersion%3D%26timeout%3D100000%26timestamp%3D1556195586976&pid=36536&qos.port=22222&registry=multicast&timestamp=1556195586971
    	System.out.println("originInvoker.getUrl():======"+originInvoker.getUrl());
    	
    	 // 获取注册中心 URL，以 zookeeper 注册中心为例（如果原来的originInvoker.getUrl()的值以registry开头，就替换成具体的协议，例如zookeeper），得到的示例 URL 如下：
        // zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F172.17.48.52%3A20880%2Fcom.alibaba.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        //从originInvoker中取出服务提供者信息（以export为key，这是ServiceConfig的642行放进去的）
        //一个providerUrl的示范：injvm://10.216.40.189:50063/org.apache.dubbo.demo.DemoService?accesslog=true&anyhost=true&application=demo-provider&bind.ip=10.216.40.189&bind.port=50063&dubbo=2.0.2&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello&notify=false&pid=37996&qos.port=22222&side=provider&specVersion=&timeout=100000&timestamp=1556196555239
        URL providerUrl = getProviderUrl(originInvoker);

        // Subscribe the override data
        // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call
        //  the same service. Because the subscribed is cached key with the name of the service, it causes the
        //  subscription information to cover.
        // 获取订阅 URL，将providerUrl的开头替换成provider，并添加几个参数category=configurators&check=false
        //例如：生成的overrideSubscribeUrl是provider://10.216.40.189:50063/org.apache.dubbo.demo.DemoService?accesslog=true&anyhost=true&application=demo-provider&bind.ip=10.216.40.189&bind.port=50063&category=configurators&check=false&dubbo=2.0.2&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello&notify=false&pid=37996&qos.port=22222&side=provider&specVersion=&timeout=100000&timestamp=1556196555239
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);
        // 创建监听器OverrideListener
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        //export invoker
        //导出服务。RegistryProtocol 可以认为并不是一个真正的协议，他是这些实际的协议（dubbo . rmi）包装者，实际export()的操作(例如dubbo)是在这里。
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        // url to registry 根据originInvoker的url初始化一个对应的Registry，例如zk对应ZookeeperRegistryFactory。
        //比如：registry形式为multicast://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=37996&qos.port=22222&timestamp=1556196555234
        final Registry registry = getRegistry(originInvoker);
        
        // 获取已注册的服务提供者 URL，比如：
        // dubbo://172.17.48.52:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&dubbo=2.0.2&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello
        final URL registeredProviderUrl = getRegisteredProviderUrl(providerUrl, registryUrl);
        
        // 向服务提供者与消费者注册表中注册服务提供者
        ProviderInvokerWrapper<T> providerInvokerWrapper = ProviderConsumerRegTable.registerProvider(originInvoker,
                registryUrl, registeredProviderUrl);
        //to judge if we need to delay publish
        // 获取 register 参数
        boolean register = registeredProviderUrl.getParameter("register", true);
        // 根据 register 的值决定是否注册服务 
        if (register) {
            register(registryUrl, registeredProviderUrl);
            providerInvokerWrapper.setReg(true);//标记向本地注册表的注册服务提供者，已经注册。
        }

        // Deprecated! Subscribe to override rules in 2.6.x or before.
        // 向注册中心进行订阅 configurators目录下面的override 数据（overrideSubscribeUrl中的目录值是configurators，它是在前面getSubscribedOverrideUrl方法中设置的），监听器是overrideSubscribeListener
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        exporter.setRegisterUrl(registeredProviderUrl);
        exporter.setSubscribeUrl(overrideSubscribeUrl);
        //Ensure that a new exporter instance is returned every time export
     // 创建并返回 DestroyableExporter
        return new DestroyableExporter<>(exporter);
    }

    private URL overrideUrlWithConfig(URL providerUrl, OverrideListener listener) {
        providerUrl = providerConfigurationListener.overrideUrl(providerUrl);
        ServiceConfigurationListener serviceConfigurationListener = new ServiceConfigurationListener(providerUrl, listener);
        serviceConfigurationListeners.put(providerUrl.getServiceKey(), serviceConfigurationListener);
        return serviceConfigurationListener.overrideUrl(providerUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker, URL providerUrl) {
    	// 获得在 `bounds` 中的缓存 Key
        String key = getCacheKey(originInvoker);
        // 从 `bounds` 获得，是不是已经暴露过服务
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            synchronized (bounds) {
                exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
                // 未暴露过，进行暴露服务
                if (exporter == null) {
                	// 创建 Invoker Delegate 对象
                    final Invoker<?> invokerDelegete = new InvokerDelegate<T>(originInvoker, providerUrl);
                    //shiwei 这里的protocol会根据invokerDelegete的getUrl()方法返回的值，即providerUrl的值来决定选用哪种protocol实现类
                    //比如：providerUrl形式为dubbo://10.216.40.189:20880/org.apache.dubbo.demo.DemoService......时，调用的是DubboProtocol
                    // 暴露服务，创建 Exporter 对象
                    exporter = new ExporterChangeableWrapper<T>((Exporter<T>) protocol.export(invokerDelegete), originInvoker);
                    bounds.put(key, exporter);
                }
            }
        }
        return exporter;
    }

    public <T> void reExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        // update local exporter
        ExporterChangeableWrapper exporter = doChangeLocalExport(originInvoker, newInvokerUrl);
        // update registry
        URL registryUrl = getRegistryUrl(originInvoker);
        final URL registeredProviderUrl = getRegisteredProviderUrl(newInvokerUrl, registryUrl);

        //decide if we need to re-publish
        ProviderInvokerWrapper<T> providerInvokerWrapper = ProviderConsumerRegTable.getProviderWrapper(registeredProviderUrl, originInvoker);
        ProviderInvokerWrapper<T> newProviderInvokerWrapper = ProviderConsumerRegTable.registerProvider(originInvoker, registryUrl, registeredProviderUrl);
        /**
         * Only if the new url going to Registry is different with the previous one should we do unregister and register.
         */
        if (providerInvokerWrapper.isReg() && !registeredProviderUrl.equals(providerInvokerWrapper.getProviderUrl())) {
            unregister(registryUrl, providerInvokerWrapper.getProviderUrl());
            register(registryUrl, registeredProviderUrl);
            newProviderInvokerWrapper.setReg(true);
        }

        exporter.setRegisterUrl(registeredProviderUrl);
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
        } else {
            final Invoker<T> invokerDelegate = new InvokerDelegate<T>(originInvoker, newInvokerUrl);
            exporter.setExporter(protocol.export(invokerDelegate));
        }
        return exporter;
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    private Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        return registryFactory.getRegistry(registryUrl);
    }
    
    /**
     * 获得注册中心 URL
     * @param originInvoker
     * @return
     */
    private URL getRegistryUrl(Invoker<?> originInvoker) {
        URL registryUrl = originInvoker.getUrl();//比如：registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F10.216.40.189%3A20880%2Forg.apache.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bind.ip%3D10.216.40.189%26bind.port%3D20880%26dubbo%3D2.0.2%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D19668%26qos.port%3D22222%26side%3Dprovider%26specVersion%3D%26timestamp%3D1551925740889&pid=19668&qos.port=22222&registry=multicast&timestamp=1551925740881
        if (REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            String protocol = registryUrl.getParameter(REGISTRY_KEY, DEFAULT_DIRECTORY);
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(REGISTRY_KEY);
        }
        return registryUrl;//multicast://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F10.216.40.189%3A20880%2Forg.apache.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bind.ip%3D10.216.40.189%26bind.port%3D20880%26dubbo%3D2.0.2%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D19668%26qos.port%3D22222%26side%3Dprovider%26specVersion%3D%26timestamp%3D1551925740889&pid=19668&qos.port=22222&timestamp=1551925740881
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param providerUrl
     * @return url to registry.
     */
    private URL getRegisteredProviderUrl(final URL providerUrl, final URL registryUrl) {
        //The address you see at the registry
        if (!registryUrl.getParameter(SIMPLE_PROVIDER_CONFIG_KEY, false)) {
            return providerUrl.removeParameters(getFilteredKeys(providerUrl)).removeParameters(
                    MONITOR_KEY, BIND_IP_KEY, BIND_PORT_KEY, QOS_ENABLE, QOS_PORT, ACCEPT_FOREIGN_IP, VALIDATION_KEY,
                    INTERFACES);
        } else {
            String[] paramsToRegistry = getParamsToRegistry(DEFAULT_REGISTER_PROVIDER_KEYS,
                    registryUrl.getParameter(EXTRA_PROVIDER_CONFIG_KEYS_KEY, new String[0]));
            return URL.valueOf(providerUrl, paramsToRegistry, providerUrl.getParameter(METHODS_KEY, (String[]) null));
        }

    }

    private URL getSubscribedOverrideUrl(URL registeredProviderUrl) {
        return registeredProviderUrl.setProtocol(PROVIDER_PROTOCOL)
                .addParameters(CATEGORY_KEY, CONFIGURATORS_CATEGORY, CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *获取服务提供者url
     * @param originInvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> originInvoker) {
        String export = originInvoker.getUrl().getParameterAndDecoded(EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + originInvoker.getUrl());
        }
        return URL.valueOf(export);
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
    	// 取 registry 参数值，并将其设置为协议头
        url = url.setProtocol(url.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY)).removeParameter(REGISTRY_KEY);
        // 获取注册中心实例
        Registry registry = registryFactory.getRegistry(url);
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        // 将 url 查询字符串转为 Map
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        // 获取 group 配置
        String group = qs.get(Constants.GROUP_KEY);
        if (group != null && group.length() > 0) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {
            	// 通过 SPI 加载 MergeableCluster 实例，并调用 doRefer 继续执行服务引用逻辑
                return doRefer(getMergeableCluster(), registry, type, url);
            }
        }
        // 调用 doRefer 继续执行服务引用逻辑
        return doRefer(cluster, registry, type, url);
    }

    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }

    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
    	// 创建 RegistryDirectory 实例
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        // 设置注册中心和协议
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        Map<String, String> parameters = new HashMap<String, String>(directory.getUrl().getParameters());
        // 生成服务消费者链接，比如consumer://10.216.40.189/org.apache.dubbo.demo.DemoService?application=demo-consumer&check=false&dubbo=2.0.2&interface=org.apache.dubbo.demo.DemoService&methods=sayHello&pid=24408&qos.port=33333&side=consumer&timestamp=1551929710554
        URL subscribeUrl = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);
        
        // 注册服务消费者，在 consumers 目录下新节点
        if (!ANY_VALUE.equals(url.getServiceInterface()) && url.getParameter(REGISTER_KEY, true)) {
            registry.register(getRegisteredConsumerUrl(subscribeUrl, url));
        }
        ///根据url获取RouterFactory的自适应扩展类，然后创建一个route链
        directory.buildRouterChain(subscribeUrl);
        
        // 订阅 providers、configurators、routers 等节点数据
        //在此方法中，会循环获得到的服务提供者列表，调用 Protocol#refer(type, url) 方法，创建每个调用服务的 Invoker 对象。
        //在订阅节点数据ZookeeperRegistry的doSubscribe最后，会回调RegistryDirectory的notify()方法，然后调用refreshOverrideAndInvoker() --> refreshInvoker()，创建Invoker对象
        directory.subscribe(subscribeUrl.addParameter(CATEGORY_KEY,
                PROVIDERS_CATEGORY + "," + CONFIGURATORS_CATEGORY + "," + ROUTERS_CATEGORY));

        // 一个注册中心可能有多个服务提供者，因此这里需要将多个服务提供者合并为一个
        Invoker invoker = cluster.join(directory);
        ProviderConsumerRegTable.registerConsumer(invoker, url, subscribeUrl, directory);
        return invoker;
    }

    private URL getRegisteredConsumerUrl(final URL consumerUrl, URL registryUrl) {
        if (!registryUrl.getParameter(SIMPLE_CONSUMER_CONFIG_KEY, false)) {
            return consumerUrl.addParameters(CATEGORY_KEY, CONSUMERS_CATEGORY,
                    CHECK_KEY, String.valueOf(false));
        } else {
            String[] paramsToRegistry = getParamsToRegistry(DEFAULT_REGISTER_CONSUMER_KEYS,
                    registryUrl.getParameter(EXTRA_CONSUMER_CONFIG_KEYS_KEY, new String[0]));
            return URL.valueOf(consumerUrl, paramsToRegistry, null).addParameters(
                    CATEGORY_KEY, CONSUMERS_CATEGORY, CHECK_KEY, String.valueOf(false));
        }
    }

    // available to test
    public String[] getParamsToRegistry(String[] defaultKeys, String[] addionalParameterKeys) {
        int additionalLen = addionalParameterKeys.length;
        String[] registryParams = new String[defaultKeys.length + additionalLen];
        System.arraycopy(defaultKeys, 0, registryParams, 0, defaultKeys.length);
        System.arraycopy(addionalParameterKeys, 0, registryParams, defaultKeys.length, additionalLen);
        return registryParams;
    }

    @Override
    public void destroy() {
        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();

        DynamicConfiguration.getDynamicConfiguration()
                .removeListener(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX, providerConfigurationListener);
    }

    //Merge the urls of configurators
    private static URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
        if (configurators != null && configurators.size() > 0) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    public static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegate(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegate) {
                return ((InvokerDelegate<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    static private class DestroyableExporter<T> implements Exporter<T> {

        private Exporter<T> exporter;

        public DestroyableExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            exporter.unexport();
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    private class OverrideListener implements NotifyListener {
        private final URL subscribeUrl;
        private final Invoker originInvoker;


        private List<Configurator> configurators;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information, is always not empty, The meaning is the same as the
         *             return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            logger.debug("original override urls: " + urls);

            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl.addParameter(CATEGORY_KEY,
                    CONFIGURATORS_CATEGORY));
            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);

            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            this.configurators = Configurator.toConfigurators(classifyUrls(matchedUrls, UrlUtils::isConfigurator))
                    .orElse(configurators);

            doOverrideIfNecessary();
        }

        public synchronized void doOverrideIfNecessary() {
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegate) {
                invoker = ((InvokerDelegate<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();
            //Merged with this configuration
            URL newUrl = getConfigedInvokerUrl(configurators, originUrl);
            newUrl = getConfigedInvokerUrl(serviceConfigurationListeners.get(originUrl.getServiceKey())
                    .getConfigurators(), newUrl);
            newUrl = getConfigedInvokerUrl(providerConfigurationListener.getConfigurators(), newUrl);
            if (!currentUrl.equals(newUrl)) {
                RegistryProtocol.this.reExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl +
                        ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(CATEGORY_KEY) == null && OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(CATEGORY_KEY, CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }
    }

    private class ServiceConfigurationListener extends AbstractConfiguratorListener {
        private URL providerUrl;
        private OverrideListener notifyListener;

        public ServiceConfigurationListener(URL providerUrl, OverrideListener notifyListener) {
            this.providerUrl = providerUrl;
            this.notifyListener = notifyListener;
            this.initWith(providerUrl.getEncodedServiceKey() + CONFIGURATORS_SUFFIX);
        }

        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            notifyListener.doOverrideIfNecessary();
        }
    }

    private class ProviderConfigurationListener extends AbstractConfiguratorListener {

        public ProviderConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        /**
         * Get existing configuration rule and override provider url before exporting.
         *
         * @param providerUrl
         * @param <T>
         * @return
         */
        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            overrideListeners.values().forEach(listener -> ((OverrideListener) listener).doOverrideIfNecessary());
        }
    }
    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter
     * exported by the protocol, and can modify the relationship at the time of override.
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final ExecutorService executor = newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;
        private URL subscribeUrl;
        private URL registerUrl;

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);

            Registry registry = RegistryProtocol.INSTANCE.getRegistry(originInvoker);
            try {
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                NotifyListener listener = RegistryProtocol.INSTANCE.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
                DynamicConfiguration.getDynamicConfiguration()
                        .removeListener(subscribeUrl.getServiceKey() + CONFIGURATORS_SUFFIX,
                                serviceConfigurationListeners.get(subscribeUrl.getServiceKey()));
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(() -> {
                try {
                    int timeout = ConfigurationUtils.getServerShutdownTimeout();
                    if (timeout > 0) {
                        logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. " +
                                "Usually, this is called when you use dubbo API");
                        Thread.sleep(timeout);
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            });
        }

        public void setSubscribeUrl(URL subscribeUrl) {
            this.subscribeUrl = subscribeUrl;
        }

        public void setRegisterUrl(URL registerUrl) {
            this.registerUrl = registerUrl;
        }
    }
}
