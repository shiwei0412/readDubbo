package org.apache.dubbo.common.bytecode;
/**
 * Registry接口的自适应扩展类
 * @author shiwei03
 *
 */
public class Registry$Adaptive implements com.maple.spi.Registry {
    public java.lang.String register(com.alibaba.dubbo.common.URL arg0, java.lang.String arg1) {
        if (arg0 == null) throw new IllegalArgumentException("url == null");
        com.alibaba.dubbo.common.URL url = arg0;
        String extName = url.getParameter("registry", "zookeeper");
        if (extName == null)
            throw new IllegalStateException("Fail to get extension(com.maple.spi.Registry) name from url(" + url.toString() + ") use keys([registry])");
        com.maple.spi.Registry extension = (com.maple.spi.Registry) ExtensionLoader.getExtensionLoader(com.maple.spi.Registry.class).getExtension(extName);
        return extension.register(arg0, arg1);
    }

    public java.lang.String discovery(com.alibaba.dubbo.common.URL arg0, java.lang.String arg1) {
        if (arg0 == null) throw new IllegalArgumentException("url == null");
        com.alibaba.dubbo.common.URL url = arg0;
        String extName = url.getParameter("registry", "zookeeper");
        if (extName == null)
            throw new IllegalStateException("Fail to get extension(com.maple.spi.Registry) name from url(" + url.toString() + ") use keys([registry])");
        com.maple.spi.Registry extension = (com.maple.spi.Registry) ExtensionLoader.getExtensionLoader(com.maple.spi.Registry.class).getExtension(extName);
        return extension.discovery(arg0, arg1);
    }
}