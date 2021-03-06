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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 * 
 * dubbo的SPI机制实现的最为核心的一个类，绝大多数实现逻辑均位于该类中
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /**
     * EXTENSION_LOADERS是一个静态的Map，存放的是<Class,ExtensionLoader>，即每个class对应一个ExtensionLoader
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    /**
     * EXTENSION_INSTANCES是静态的Map，存放的是<Class, Object>，即每个class对应一个class的实例对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================

    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    /**
     * 缓存的是拓展名与拓展类的映射。
     * 
     *  和cachedClasses的 KV 对调
     *  
     *  key：以等于号 = 为界，截取之后的value，类的全限定名
     *  value： 以等于号 = 为界，截取之前的key
     *  
     *  通过 {@link #loadExtensionClasses} 加载
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();

    /**
     * cachedClasses存放的是所有的拓展类，与上面的cachedNames的KV值对调
     * 
     * key:以等于号 = 为界，截取之前的key
     * value：以等于号 = 为界，截取之后的value，类的全限定名
     * 
     * 缓存的拓展实现类集合。
     * 不包含如下两种类型：
     * 1. 自适应拓展实现类。例如 AdaptiveExtensionFactory
     * 2. 带唯一参数为拓展接口的构造方法的实现类，或者说拓展 Wrapper 实现类。例如，ProtocolFilterWrapper 。
     * 		拓展 Wrapper 实现类，会添加到 {@link #cachedWrapperClasses} 中
     * 
     * 通过 {@link #loadExtensionClasses} 加载
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

    /**
     * 拓展名与 @Activate 的映射
     * 
     * 例如，AccessLogFilter在被激活时，放入cachedActivates时，
     * 形式是：accesslog=@org.apache.dubbo.common.extension.Activate(after=[], before=[], value=[accesslog], order=0, group=[provider])
     * 
     * 用于 {@link #getActivateExtension(URL, String)}
     */
    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<String, Object>();
    
    /**
     * 缓存的拓展对象集合
     * key：拓展名
     * value：拓展对象
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    
    //缓存的自适应( Adaptive )拓展对象
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    
    //缓存的自适应拓展对象的类
    private volatile Class<?> cachedAdaptiveClass = null;
    
    //缓存的默认拓展名
    private String cachedDefaultName;
    
    /**
     *  创建 {@link #cachedAdaptiveInstance} 时发生的异常。
     *  发生异常后，不再创建，参见 {@link #createAdaptiveExtension()}
     */
    private volatile Throwable createAdaptiveInstanceError;

    /**
     * 拓展 Wrapper 实现类集合
     * 拓展类：即带唯一参数为拓展接口的构造方法的实现类,例如：ProtocolFilterWrapper.java
     */
    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    private ExtensionLoader(Class<?> type) {
        this.type = type;
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * EXTENSION_LOADERS是一个Map，存放的是<Class,ExtensionLoader>，即每个类对应一个ExtensionLoader
     * @param type
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        //必须是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        //必须包含@SPI注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }
        //从缓存中取值，如果不存在则创建，然后放入缓存中。
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    private static ClassLoader findClassLoader() {
        return ClassHelper.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     * 
     * TODO shiwei  获得自动激活扩展列表方法
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *获得符合自动激活条件的拓展对象数组
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names   Dubbo URL 参数名
     * @param group group 过滤分组名
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    
    //获取Filter的自动扩展类的入参是：invoker, "reference.filter", "provider"
    public List<T> getActivateExtension(URL url, String key, String group) {
    	// 从 Dubbo URL 获得参数值
        String value = url.getParameter(key);
        //获得符合自动激活条件的拓展对象数组
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *获得符合自动激活条件的拓展对象数组
     * @param url    url
     * @param values extension point names  
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    //values的值是使用url.getParameter(key)取出来的，优先级比较高，如果url.getParameter(key)取出来的为null则values是null；如果不为null，则是分隔后的数组。
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        //将传递过来的values包装成List类型的names
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);

        //首先处理自动激活的扩展对象们
        //判断values列表中是否包含默认值“-default”，如果不包含则开始遍历map对象cachedActivates中的所有activate。
        //如果存在"-default"，表示移除所有默认配置。例如，<dubbo:service filter="-default" /> ，代表移除所有默认过滤器。
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
        	//获取这个类型的所有扩展实现类数组
            getExtensionClasses();
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();// 获取扩展的名称
                Object activate = entry.getValue();//获取扩展的注解，cachedActivates的值是在loadClass()方法中put进去的。

                String[] activateGroup, activateValue;

                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                //匹配分组
                //1. 如果activate注解的group没有设定，直接返回true
                //2. 如果设定了，需要和传入的额group进行比较，看是否包含其中，如果包含，返回true
                if (isMatchGroup(group, activateGroup)) {
                    T ext = getExtension(name);//group 校验通过了，从缓存中获取此name对应的实例，缓存中没有则创建。
                    if (!names.contains(name)  //names(自定义配置) 不包含 遍历此时的name
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)  //names(自定义配置)中不包含"-"+遍历此时的name,例如 <dubbo:service filter="-monitor" />，则 MonitorFilter 会被移除。
                            && isActive(activateValue, url)) { //通过URL判断这个activate注解是否激活状态。例如AccessLogFilter的@Activate注解的value值是accesslog，当URL中包含了accesslog且accesslog对应值不为空的情况下，才是激活的。
                        exts.add(ext);//增加扩展
                    }
                }
            }
            //按照Activate的方式进行排序，注意order
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        
        /**
	        * 借用usrs这个临时变量，进行循环往exts中塞具体的ext的对象。
	        * 如果碰到了"default"就把当前已经获得的自定义扩展对象usrs添加到头部，清空usrs这个临时变量。
	        * 如果没有"default"那么usrs不会清空，所以下面有个if，当usrs不为空将里面的内容增加到exts中
        */
        // 然后处理自定义配置的拓展对象们。例如在 <dubbo:service filter="demo" /> ，代表需要加入 DemoFilter
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {// 判断非移除的
            	// 将配置的自定义在自动激活的拓展对象们前面。例如，<dubbo:service filter="demo,default,demo2" /> ，则 DemoFilter 就会放在默认的过滤器前面。
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                	// 获得拓展对象
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        // 添加到结果集
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
    	/**
         * 如果@Activate注解中的value是空的直接返回true
         */
        if (keys.length == 0) {
            return true;
        }
        
        /**
         * 从activate.value()拿到的数据进行遍历
         */
        for (String key : keys) {
        	/**
             * 从URL中获取参数，进行遍历，如果有一个参数同key一致，或者是以.key的方式结尾。
             */
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     * 
     * 获取拓展类对象
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
        	//name为true表示获取默认的拓展实现类
            return getDefaultExtension();
        }
        //Holder用于持有目标对象，holder里面存放着拓展类对象。cachedInstances的key是扩展名，value是扩展对象
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        //双重检查
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                	//如果没有实例，就创建拓展类实例，并set到holder中
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获取自适应拓展实例的入口方法,实例方法。
     * 
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
    	//从缓存中获取自适应扩展实例
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                        	//如果缓存中没有，则创建自适应拓展实例
                            instance = createAdaptiveExtension();
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 根据扩展名创建对应的扩展对象
     * 
     * 如果有包装类的话，会返回包装类的实例
     * @param name
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
    	// 根据name获取到对应的扩展类
    	// getExtensionClasses()方法是从配置文件中加载所有的拓展类，可得到"配置项名称-配置类”的映射关系表，存放到cachedClasses中
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
        	//从扩展类的实例缓存中取class对应的实例，如果没有，则自己实例化一个
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            //注入依赖的属性，可以是扩展对象，也可以是spring中的Bean对象
            injectExtension(instance);
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * Dubbo IOC 是通过 setter 方法注入依赖。Dubbo 首先会通过反射获取到实例的所有方法，然后再遍历方法列表，
     * 检测方法名是否具有 setter 方法特征。若有，则通过 ObjectFactory 获取依赖对象，最后通过反射调用 setter 方法将依赖设置到目标对象中
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        try {
        	//objectFactory 变量的类型为 AdaptiveExtensionFactory，AdaptiveExtensionFactory 内部维护了一个 ExtensionFactory 列表，
        	//用于存储其他类型的 ExtensionFactory。Dubbo 目前提供了两种 ExtensionFactory，分别是 SpiExtensionFactory 和 
        	//SpringExtensionFactory。前者用于创建自适应的拓展，后者是用于从 Spring 的 IOC 容器中获取所需的拓展。
            if (objectFactory != null) {
                for (Method method : instance.getClass().getMethods()) {
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                        /**
                         * Check {@link DisableInject} to see if we need auto injection for this property
                         */
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }
                        Class<?> pt = method.getParameterTypes()[0];
                        if (ReflectUtils.isPrimitives(pt)) {
                            continue;
                        }
                        try {
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            
                            //这里通过 ObjectFactory 获取依赖对象，最后通过反射调用 setter 方法将依赖设置到目标对象中。
                            //芋道源码：实际获取的不仅仅是拓展对象，也可以是 Spring Bean 对象。
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /**
     * 获得拓展实现类数组。
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    // synchronized in getExtensionClasses
    //通过SPI加载接口对应的所有实现类
    //此方法已经getExtensionClasses方法同步过。
    private Map<String, Class<?>> loadExtensionClasses() {
    	//解析type接口上的SPI注解
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
        	//获取注解标记值，标记值表示默认的扩展实现类,例如Protocol.java的标注就是dubbo
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) {
                    cachedDefaultName = names[0];
                }
            }
        }

        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }

    /**
     * @param extensionClasses
     * @param dir  目录
     * @param type 接口的全限定名
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
    	// 完整的文件名
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            //加载spi的配置文件
            // 获得文件名对应的所有文件数组
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
            	// 遍历文件数组
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    //读取和解析配置文件
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    //loadResource方法用于读取和解析配置文件，并通过反射加载类，最后调用 loadClass 方法进行其他操作
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                	//被注释掉的行跳过
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            // 以等于号 = 为界，截取键与值，例如：dubbo=org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                            	//使用反射来加载类，并通过 loadClass 方法对类进行缓存
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", "
                            		+ "class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * @param extensionClasses
     * @param resourceURL
     * @param clazz   以等于号 = 为界，截取之后的value，类的全限定名
     * @param name    以等于号 = 为界，截取之前的key
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
    	//判断type接口是clazz类的接口，所有的clazz都应该是type的实现类。
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }
        //java.lang.Package.isAnnotationPresent(Class<? extends Annotation> annotationClass) 方法返回true，如果指定类型的注释存在于此元素上,
        //否则返回false。这种方法的设计主要是为了方便访问标记注释
        //判断接口实现类是否标注了Adaptive注解。（Adaptive注解是java的自适应拓展机制）
        //这里注意区分类上的Adaptive注解和方法上的Adaptive注解，dubbo中只有两个类上标注了Adaptive注解，分别是AdaptiveCompiler、AdaptiveExtensionFactory
        if (clazz.isAnnotationPresent(Adaptive.class)) {//实现了
            if (cachedAdaptiveClass == null) {
                cachedAdaptiveClass = clazz;// 设置 cachedAdaptiveClass缓存
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }
        } else if (isWrapperClass(clazz)) {//是否包装类
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;// 存储 clazz 到 cachedWrapperClasses 缓存中
            }
            wrappers.add(clazz);
        } else {// 程序进入此分支，表明 clazz 是一个普通的拓展类
            clazz.getConstructor();// 检测 clazz 是否有默认的构造方法，如果没有，则抛出异常
            if (name == null || name.length() == 0) {
                name = findAnnotationName(clazz);// 如果 name 为空，则尝试从扩展类的注解中获取 name，或使用小写的类名作为 name
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {
            	
                Activate activate = clazz.getAnnotation(Activate.class);
                if (activate != null) {
                	// 如果类上有 Activate 注解，则使用 names 数组的第一个元素作为键，
                    // 存储 name 到 Activate 注解对象的映射关系
                    cachedActivates.put(names[0], activate);
                } else {
                    // support com.alibaba.dubbo.common.extension.Activate
                    com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
                    if (oldActivate != null) {
                        cachedActivates.put(names[0], oldActivate);
                    }
                }
                for (String n : names) {
                    if (!cachedNames.containsKey(clazz)) {
                    	// 存储 Class 到名称的映射关系
                        cachedNames.put(clazz, n);
                    }
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                    	// 存储名称到 Class 的映射关系
                        extensionClasses.put(n, clazz);
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
    	//尝试从扩展类的注解中获取 name，如果注解为空，则使用小写的类名作为 name
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
        	//(T) getAdaptiveExtensionClass().newInstance()创建class对象实例
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());// 获取自适应拓展类，并通过反射实例化
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }
    
    /**
     * 首先从第一个逻辑说起，getExtensionClasses 这个方法用于获取某个接口的所有实现类。比如该方法可以获取 Protocol 接口的
     *  DubboProtocol、HttpProtocol、InjvmProtocol 等实现类。在获取实现类的过程中，如果某个某个实现类被 Adaptive 注解修饰了，
     *  那么该类就会被赋值给 cachedAdaptiveClass 变量。此时，上面步骤中的第二步条件成立（缓存不为空），直接返回 
     *  cachedAdaptiveClass 即可。如果所有的实现类均未被 Adaptive 注解修饰，那么执行第三步逻辑，创建自适应拓展类。
     * @return
     */
    
    private Class<?> getAdaptiveExtensionClass() {
    	//加载接口的所有扩展类到缓存中
        getExtensionClasses();
        //检查缓存的cachedAdaptiveClass，若不为空，则直接返回缓存。
        //注意：dubbo中只有两个类上标注了Adaptive注解，分别是AdaptiveCompiler、AdaptiveExtensionFactory）
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        return cachedAdaptiveClass = createAdaptiveExtensionClass();// 创建自适应拓展类
    }

    private Class<?> createAdaptiveExtensionClass() {
    	// 构建自适应拓展代码
        String code = createAdaptiveExtensionClassCode();
        ClassLoader classLoader = findClassLoader();
        // 获取编译器实现类
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(
        		org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 编译代码，生成 Class
        return compiler.compile(code, classLoader);
    }

    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;
        // 通过反射获取所有的方法
        for (Method m : methods) {
        	// 检测方法上是否有 Adaptive 注解
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveAnnotation) {
        	// 若所有的方法上均无 Adaptive 注解，则抛出异常
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");
        }
       
        
        /**
         * 以 Dubbo 的 Protocol 接口为例，生成的代码如下：
			package com.alibaba.dubbo.rpc;
			import com.alibaba.dubbo.common.extension.ExtensionLoader;
			public class Protocol$Adaptive implements com.alibaba.dubbo.rpc.Protocol {
			    // 省略方法代码
			}
         */
        
        // 生成 package 代码：package + type 所在包
        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        // 生成 import 代码：import + ExtensionLoader 全限定名
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        // 生成类代码：public class + type简单名称 + $Adaptive + implements + type全限定名 + {
        codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");
        
        /**
         * 前面说过方法代理逻辑会从 URL 中提取目标拓展的名称，因此代码生成逻辑的一个重要的任务是从方法的参数列表或者其他参数中获取 URL 数据。
         */
        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            if (adaptiveAnnotation == null) {// 如果方法上无 Adaptive 注解，则生成 throw new UnsupportedOperationException(...) 代码
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                int urlTypeIndex = -1;
                // 遍历参数列表，确定 URL 参数位置
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                // 这一大段的主要逻辑就是获取到URL数据，并为之生成判空和赋值代码。
                if (urlTypeIndex != -1) {// urlTypeIndex != -1，表示参数列表中存在 URL 参数
                    // Null Point check 为 URL 类型参数生成判空代码，格式如下
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);
                    // 为 URL 类型参数生成赋值代码，形如 URL url = arg1
                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type 参数列表中不存在 URL 类型参数
                else {
                    String attribMethod = null;

                    // find URL getter method
                    LBL_PTS:
                    // 遍历方法的参数类型列表
                    for (int i = 0; i < pts.length; ++i) {
                    	// 获取某一类型参数的全部方法
                        Method[] ms = pts[i].getMethods();
                        // 遍历方法列表，寻找可返回 URL 的 getter 方法
                        for (Method m : ms) {
                            String name = m.getName();
                            // 1. 方法名以 get 开头，或方法名大于3个字符
                            // 2. 方法的访问权限为 public
                            // 3. 非静态方法
                            // 4. 方法参数数量为0
                            // 5. 方法返回值类型为 URL
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {// 如果所有参数中均不包含可返回 URL 的 getter 方法，则抛出异常
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    // 为可返回 URL 的参数生成判空代码，格式如下：
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    
                    // 为 getter 方法返回的 URL 生成判空代码，格式如下：
                    // if (argN.getter方法名() == null) 
                    //     throw new IllegalArgumentException(参数全限定名 + argument getUrl() == null);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    // 生成赋值语句，格式如下：
                    // URL全限定名 url = argN.getter方法名()，比如 
                    // com.alibaba.dubbo.common.URL url = invoker.getUrl();
                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                //若方法上有Adaptive注解，对应前面的 “无 Adaptive 注解方法代码生成逻辑”
                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                if (value.length == 0) {//如果注解没有值，取扩展接口的类名（不含包名）并处理一下，例如Protocol
                    String splitName = StringUtils.camelToSplitName(type.getSimpleName(), ".");
                    value = new String[]{splitName};
                }

                //检测方法列表中是否存在 Invocation 类型的参数，若存在，则为其生成判空代码和其他一些代码。
                boolean hasInvocation = false;
                // 遍历参数类型列表
                for (int i = 0; i < pts.length; ++i) {
                	// 判断当前参数名称是否等于 com.alibaba.dubbo.rpc.Invocation
                    if (("org.apache.dubbo.rpc.Invocation").equals(pts[i].getName())) {
                        // Null Point check
                    	// 为 Invocation 类型参数生成判空代码
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        // 生成 getMethodName 方法调用代码，格式为： String methodName = argN.getMethodName();
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                // 设置默认拓展名，cachedDefaultName 源于 SPI 注解值，默认情况下，SPI 注解值为空串，此时 cachedDefaultName = null
                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                // 遍历 value，这里的 value 是 Adaptive 的注解值，此处循环目的是生成从 URL 中获取拓展名的代码，生成的代码会赋值给 getNameCode 变量。
                // 注意这个循环的遍历顺序是由后向前遍历的。
                //例如:Transporter.java的Adaptive注解就有多个值，LoadBalance就只有一个值，Cluster就没有值。
                for (int i = value.length - 1; i >= 0; --i) {
                    if (i == value.length - 1) {// 当 i 为最后一个元素的坐标时
                        if (null != defaultExtName) {// 如果默认拓展名非空
                        	// protocol 是 url 的一部分，可通过 getProtocol 方法获取，其他的则是从URL 参数中获取。因为获取方式不同，所以这里要判断 value[i] 是否为 protocol
                            if (!"protocol".equals(value[i])) {// hasInvocation 用于标识方法参数列表中是否有 Invocation 类型参数
                            	// 生成的代码功能等价于下面的代码：url.getMethodParameter(methodName, value[i], defaultExtName)
                                // 以 LoadBalance 接口的 select 方法为例，最终生成的代码如下： url.getMethodParameter(methodName, "loadbalance", "random")
                            	//TODO shiwei 这里hasInvocation属性没怎么看懂？
                                if (hasInvocation) {
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                } else {
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                                }
                            } else {
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                            }
                        } else {//如果 默认拓展名为空
                            if (!"protocol".equals(value[i])) {
                                if (hasInvocation) {
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                } else {
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                                }
                            } else {
                                getNameCode = "url.getProtocol()";
                            }
                        }
                    } else {//如果i不是最后一个元素的坐标时
                        if (!"protocol".equals(value[i])) {
                            if (hasInvocation) {
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            } else {
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                            }
                        } else {
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                        }
                    }
                }
                // 生成 extName 赋值代码
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                // 生成 extName 判空代码
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);

                // 生成拓展获取代码，格式如下：
                // type全限定名 extension = (type全限定名)ExtensionLoader全限定名
                //     .getExtensionLoader(type全限定名.class).getExtension(extName);
                // Tips: 格式化字符串中的 %<s 表示使用前一个转换符所描述的参数，即 type 全限定名
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // return statement
                // 如果方法返回值类型非 void，则生成 return 语句。
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                // 生成目标方法调用逻辑，格式为：extension.方法名(arg0, arg2, ..., argN);
                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0) {
                        code.append(", ");
                    }
                    code.append("arg").append(i);
                }
                code.append(");");
            }
            //以 Protocol 接口举例说明，上面代码生成的内容如下：
			//com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol) ExtensionLoader
			//    .getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class).getExtension(extName);
			//return extension.refer(arg0, arg1);

            
            //收尾工作。  public + 返回值全限定名 + 方法名 + (
            codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");
            
            // 添加参数列表代码
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");
            
            // 添加异常抛出代码
            if (ets.length > 0) {
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");
            codeBuilder.append(code.toString());
            codeBuilder.append("\n}");
        }
        codeBuilder.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
