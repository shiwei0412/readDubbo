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
package org.apache.dubbo.rpc.cluster.router.condition;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConditionRouter
 *
 */
public class ConditionRouter extends AbstractRouter implements Comparable<Router> {
    public static final String NAME = "condition";

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);
    protected static Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    protected Map<String, MatchPair> whenCondition;
    protected Map<String, MatchPair> thenCondition;

    private boolean enabled;

    public ConditionRouter(String rule, boolean force, boolean enabled) {
        this.force = force;
        this.enabled = enabled;
        this.init(rule);
    }

    public ConditionRouter(URL url) {
        this.url = url;
        // 获取 priority、force 配置、enabled配置
        this.priority = url.getParameter(Constants.PRIORITY_KEY, 0);
        this.force = url.getParameter(Constants.FORCE_KEY, false);
        this.enabled = url.getParameter(Constants.ENABLED_KEY, true);
        init(url.getParameterAndDecoded(Constants.RULE_KEY));// url.getParameterAndDecoded(Constants.RULE_KEY)表示获取路由规则
    }

    /**例如：
     * host = 10.20.153.10 => host = 10.20.153.11
该条规则表示 IP 为 10.20.153.10 的服务消费者只可调用 IP 为 10.20.153.11 机器上的服务，不可调用其他机器上的服务。条件路由规则的格式如下：
[服务消费者匹配条件] => [服务提供者匹配条件]
     * @param rule
     */
    public void init(String rule) {
        try {
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }
            rule = rule.replace("consumer.", "").replace("provider.", "");
            // 定位 => 分隔符
            int i = rule.indexOf("=>");
            // 分别获取服务消费者和提供者匹配规则
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
            // 解析服务消费者匹配规则
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            // 解析服务提供者匹配规则
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
            // 将解析出的匹配规则分别赋值给 whenCondition 和 thenCondition 成员变量
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
    	// 定义条件映射集合
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // Key-Value pair, stores both match and mismatch conditions
        MatchPair pair = null;
        // Multiple values
        Set<String> values = null;
        
	    // 通过正则表达式匹配路由规则，ROUTE_PATTERN = ([&!=,]*)\s*([^&!=,\s]+)
	    // 这个表达式看起来不是很好理解，第一个括号内的表达式用于匹配"&", "!", "=" 和 "," 等符号。
	    // 第二括号内的用于匹配英文字母，数字等字符。举个例子说明一下：
	    //    host = 2.2.2.2 & host != 1.1.1.1 & method = hello
	    // 匹配结果如下：
	    //     括号一      括号二
	    // 1.  null       host
	    // 2.   =         2.2.2.2
	    // 3.   &         host
	    // 4.   !=        1.1.1.1 
	    // 5.   &         method
	    // 6.   =         hello
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        
        /**
         *	循环结束：
         * {
		    "host": {
		        "matches": ["2.2.2.2"],
		        "mismatches": ["1.1.1.1"]
		    },
		    "method": {
		        "matches": ["hello"],
		        "mismatches": []
		    }
			}
         */
        while (matcher.find()) { // Try to match one by one
            String separator = matcher.group(1);	// 获取括号一内的匹配结果
            String content = matcher.group(2); // 获取括号二内的匹配结果
            // Start part of the condition expression.
            if (separator == null || separator.length() == 0) {// 分隔符为空，表示匹配的是表达式的开始部分
                pair = new MatchPair();// 创建 MatchPair 对象
                condition.put(content, pair);// 存储 <匹配项, MatchPair> 键值对，比如 <host, MatchPair>
            }
            // The KV part of the condition expression
            else if ("&".equals(separator)) {// 如果分隔符为 &，表明接下来也是一个条件
                if (condition.get(content) == null) {//尝试从 condition 获取 MatchPair
                    pair = new MatchPair();// 未获取到 MatchPair，重新创建一个，并放入 condition 中
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }
            // The Value in the KV part.
            else if ("=".equals(separator)) {// 分隔符为 =
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.matches;// 将 content 存入到 MatchPair 的 matches 集合中
                values.add(content);
            }
            // The Value in the KV part.
            else if ("!=".equals(separator)) {//  分隔符为 != 
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.mismatches;// 将 content 存入到 MatchPair 的 mismatches 集合中
                values.add(content);
            }
            // The Value in the KV part, if Value have more than one items.
            else if (",".equals(separator)) { // Should be separated by ',' // 分隔符为 ,
                if (values == null || values.isEmpty()) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }
                values.add(content);// 将 content 存入到上一步获取到的 values 中，可能是 matches，也可能是 mismatches
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    
    /**
     * invoker：指服务提供者provider列表
     * url 服务消费者url
     * invocation：指服务rpc调用相关参数信息
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        if (!enabled) {
            return invokers;
        }

        if (invokers == null || invokers.isEmpty()) {
            return invokers;
        }
        try {
        	
    	// 先对服务消费者条件进行匹配，如果匹配失败，表明服务消费者 url 不符合匹配规则，
        // 无需进行后续匹配，直接返回 Invoker 列表即可。比如下面的规则：
        //     host = 10.20.153.10 => host = 10.0.0.10
        // 这条路由规则希望 IP 为 10.20.153.10 的服务消费者调用 IP 为 10.0.0.10 机器上的服务。
        // 当消费者 ip 为 10.20.153.11 时，matchWhen 返回 false，表明当前这条路由规则不适用于
        // 当前的服务消费者，此时无需再进行后续匹配，直接返回即可。
            if (!matchWhen(url, invocation)) {
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();
            // 服务提供者匹配条件未配置，表明对指定的服务消费者禁用服务，也就是服务消费者在黑名单中
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            
            // 这里可以简单的把 Invoker 理解为服务提供者，现在使用服务提供者匹配规则对 
            // Invoker 列表进行匹配
            for (Invoker<T> invoker : invokers) {
            	// 若匹配成功，表明当前 Invoker 符合服务提供者匹配规则。
                // 此时将 Invoker 添加到 result 列表中
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }
            //返回匹配结果，如果 result 为空列表，且 force = true，表示强制返回空列表，
            // 否则路由结果为空的路由规则将自动失效
            if (!result.isEmpty()) {
                return result;
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(Constants.RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        // 原样返回，此时 force = false，表示该条路由规则失效
        return invokers;
    }

    @Override
    public boolean isRuntime() {
        // We always return true for previously defined Router, that is, old Router doesn't support cache anymore.
//        return true;
        return this.url.getParameter(Constants.RUNTIME_KEY, false);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    /**
     * @param url 消费者url
     * @param invocation 调用参数
     * @return
     */
    boolean matchWhen(URL url, Invocation invocation) {
    	// 服务消费者条件为 null 或空，均返回 true，比如：
        //     => host != 172.22.3.91
        // 表示所有的服务消费者都不得调用 IP 为 172.22.3.91 的机器上的服务
        return whenCondition == null || whenCondition.isEmpty() || matchCondition(whenCondition, url, null, invocation);
    }

    /**
     * @param url 服务提供者url
     * @param param 服务消费者url
     * @return
     */
    private boolean matchThen(URL url, URL param) {
    	// 服务提供者条件为 null 或空，表示禁用服务
        return !(thenCondition == null || thenCondition.isEmpty()) && matchCondition(thenCondition, url, param, null);
    }

    /**
     * 
     * @param condition 匹配条件
     * @param url   服务消费者或提供者url
     * @param param 匹配消费者传入值是null，匹配提供者时传入值是服务消费者url
     * @param invocation 匹配消费者传入值是invocation，匹配提供者时传入值是null
     * @return
     */
    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
    	/**
    	 * 将服务提供者或消费者 url 转成 Map
    	 */
        Map<String, String> sample = url.toMap();
        boolean result = false;
        // 遍历 condition 列表
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();// 获取匹配项名称，比如 host、method 等
            String sampleValue;
            //get real invoked method name from invocation
            // 如果 invocation 不为空，且 key 为 mehtod(s)，表示进行方法匹配
            if (invocation != null && (Constants.METHOD_KEY.equals(key) || Constants.METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();
            } else if (Constants.ADDRESS_KEY.equals(key)) {
                sampleValue = url.getAddress();// 从服务提供者或消费者 url 中获取指定字段值
            } else if (Constants.HOST_KEY.equals(key)) {
                sampleValue = url.getHost();// 从服务提供者或消费者 url 中获取指定字段值
            } else {
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    sampleValue = sample.get(Constants.DEFAULT_KEY_PREFIX + key);
                }
            }
            if (sampleValue != null) {
            	// 调用 MatchPair 的 isMatch 方法进行匹配
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    result = true;
                }
            } else {
            	// sampleValue 为空，表明服务提供者或消费者 url 中不包含相关字段。此时如果 
                // MatchPair 的 matches 不为空，表示匹配失败，返回 false。比如我们有这样
                // 一条匹配条件 loadbalance = random，假设 url 中并不包含 loadbalance 参数，
                // 此时 sampleValue = null。既然路由规则里限制了 loadbalance 必须为 random，
                // 但 sampleValue = null，明显不符合规则，因此返回 false
                //not pass the condition
                if (!matchPair.getValue().matches.isEmpty()) {
                    return false;
                } else {
                    result = true;
                }
            }
        }
        return result;
    }

    protected static final class MatchPair {
    	/**
    	 *  内部包含了两个 Set 类型的成员变量，分别用于存放匹配和不匹配的条件。这个类两个成员变量会在 parseRule 方法中被用到
    	 */
        final Set<String> matches = new HashSet<String>();
        final Set<String> mismatches = new HashSet<String>();

        private boolean isMatch(String value, URL param) {
        	// 情况一：matches 非空，mismatches 为空
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }
            // 情况二：matches 为空，mismatches 非空
            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                return true;
            }
            // 情况三：matches 非空，mismatches 非空
            if (!matches.isEmpty() && !mismatches.isEmpty()) {
                //when both mismatches and matches contain the same value, then using mismatches first
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }
            // 情况四：matches 和 mismatches 均为空，此时返回 false
            return false;
        }
    }
}
