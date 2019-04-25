package org.apache.dubbo.common.bytecode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * createProxy生成的ccp
 * @author shiwei03
 *
 */
public class proxy01 implements ClassGenerator.DC, EchoService, DemoService {
    public static Method[] methods;
    private InvocationHandler handler;
    //实现了接口方法
    public String sayHello(String var1) {
        Object[] var2 = new Object[]{var1};
        Object var3 = null;
        try {
            var3 = this.handler.invoke(this, methods[1], var2);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return (String)var3;
    }

    public Object $echo(Object var1) {
        Object[] var2 = new Object[]{var1};
        Object var3 = null;
        try {
            var3 = this.handler.invoke(this, methods[3], var2);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return (Object)var3;
    }

    public proxy01() {
    }
    //public 构造函数，这里handler是
    //由Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker))语句传入的InvokerInvocationHandler对象
    public proxy01(InvocationHandler var1) {
        this.handler = var1;
    }
}