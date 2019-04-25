package org.apache.dubbo.common.bytecode;
 
 import com.alibaba.dubbo.demo.provider.DemoDAO;
 import com.alibaba.dubbo.demo.provider.DemoServiceImpl;
 import java.lang.reflect.InvocationTargetException;
 import java.util.Map;
 
 /**
  *芋道源码： 一个生成的wrapper类
  * @author shiwei03
  *
  */
 public class Wrapper1
   extends Wrapper
   implements ClassGenerator.DC
 {
   public static String[] pns;
   public static Map pts;
   public static String[] mns;
   public static String[] dmns;
   public static Class[] mts0;
   public static Class[] mts1;
   public static Class[] mts2;
 
   public String[] getPropertyNames()
   {
     return pns;
   }
 
   public boolean hasProperty(String paramString)
   {
     return pts.containsKey(paramString);
   }
 
   public Class getPropertyType(String paramString)
   {
     return (Class)pts.get(paramString);
   }
 
   public String[] getMethodNames()
   {
     return mns;
   }
 
   public String[] getDeclaredMethodNames()
   {
     return dmns;
   }
 
   public void setPropertyValue(Object paramObject1, String paramString, Object paramObject2)
   {
     DemoServiceImpl w;
     try
     {
       w = (DemoServiceImpl)paramObject1;
     }
     catch (Throwable localThrowable)
     {
       throw new IllegalArgumentException(localThrowable);
     }
     if (paramString.equals("test01"))
     {
       w.test01 = ((String)paramObject2);
       return;
     }
     if (paramString.equals("demoDAO"))
     {
       localDemoServiceImpl.setDemoDAO((DemoDAO)paramObject2);
       return;
     }
     throw new NoSuchPropertyException("Not found property \"" + paramString + "\" filed or setter method in class com.alibaba.dubbo.demo.provider.DemoServiceImpl.");
   }
 
   public Object getPropertyValue(Object paramObject, String paramString)
   {
     DemoServiceImpl w;
     try
     {
       w = (DemoServiceImpl)paramObject;
     }
     catch (Throwable localThrowable)
     {
       throw new IllegalArgumentException(localThrowable);
     }
     if (paramString.equals("test01")) {
       return localDemoServiceImpl.test01;
     }
     throw new NoSuchPropertyException("Not found property \"" + paramString + "\" filed or setter method in class com.alibaba.dubbo.demo.provider.DemoServiceImpl.");
   }
 
   public Object invokeMethod(Object paramObject, String paramString, Class[] paramArrayOfClass, Object[] paramArrayOfObject)
     throws InvocationTargetException
   {
     DemoServiceImpl w;
     try
     {
       w = (DemoServiceImpl)paramObject;
     }
     catch (Throwable localThrowable1)
     {
       throw new IllegalArgumentException(localThrowable1);
     }
     try
     {
       if ("sayHello".equals(paramString) && paramArrayOfClass.length == 1) {
         return w.sayHello((String)paramArrayOfObject[0]);
       }
       if ("bye".equals(paramString) && paramArrayOfClass.length == 1)
       {
         w.bye((Object)paramArrayOfObject[0]);
         return null;
       }
       if ("setDemoDAO".equals(paramString) && paramArrayOfClass.length == 1)
       {
           w.setDemoDAO((DemoDAO)paramArrayOfObject[0]);
           return null;
         }
       }
       catch (Throwable localThrowable2)
       {
         throw new InvocationTargetException(localThrowable2);
       }
       throw new NoSuchMethodException("Not found method \"" + paramString + "\" in class com.alibaba.dubbo.demo.provider.DemoServiceImpl.");
     }
   }

