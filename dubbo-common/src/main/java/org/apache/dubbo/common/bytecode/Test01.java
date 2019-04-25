package org.apache.dubbo.common.bytecode;

/**
 * org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl 使用javassist进行增强后的形式
 * @author shiwei03
 *
 */
public class Test01 {

	public Object invokeMethod(Object o, String n, Class[] p, Object[] v)
			throws java.lang.reflect.InvocationTargetException {
		org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl w;
		try {
			w = ((org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl) $1);
		} catch (Throwable e) {
			throw new IllegalArgumentException(e);
		}
		try {
			if ("invoke".equals($2) && $3.length == 2) {
				return ($w) w.invoke((java.lang.String) $4[0], (java.lang.String) $4[1]);
			}
			if ("add".equals($2) && $3.length == 2) {
				return ($w) w.add(((Number) $4[0]).intValue(), ((Number) $4[1]).longValue());
			}
			if ("get".equals($2) && $3.length == 1) {
				return ($w) w.get((org.apache.dubbo.rpc.protocol.dubbo.support.CustomArgument) $4[0]);
			}
			if ("timestamp".equals($2) && $3.length == 0) {
				return ($w) w.timestamp();
			}
			if ("keys".equals($2) && $3.length == 1) {
				return ($w) w.keys((java.util.Map) $4[0]);
			}
			if ("getType".equals($2) && $3.length == 1) {
				return ($w) w.getType((org.apache.dubbo.rpc.protocol.dubbo.support.Type) $4[0]);
			}
			if ("getSize".equals($2) && $3.length == 1 && $3[0].getName().equals("[Ljava.lang.Object;")) {
				return ($w) w.getSize((java.lang.Object[]) $4[0]);
			}
			if ("getSize".equals($2) && $3.length == 1 && $3[0].getName().equals("[Ljava.lang.String;")) {
				return ($w) w.getSize((java.lang.String[]) $4[0]);
			}
			if ("echo".equals($2) && $3.length == 1 && $3[0].getName().equals("java.lang.String")) {
				return ($w) w.echo((java.lang.String) $4[0]);
			}
			if ("echo".equals($2) && $3.length == 1 && $3[0].getName().equals("java.util.Map")) {
				return ($w) w.echo((java.util.Map) $4[0]);
			}
			if ("stringLength".equals($2) && $3.length == 1) {
				return ($w) w.stringLength((java.lang.String) $4[0]);
			}
			if ("enumlength".equals($2) && $3.length == 1) {
				return ($w) w.enumlength((org.apache.dubbo.rpc.protocol.dubbo.support.Type[]) $4[0]);
			}
			if ("sayHello".equals($2) && $3.length == 1) {
				w.sayHello((java.lang.String) $4[0]);
				return null;
			}
			if ("nonSerializedParameter".equals($2) && $3.length == 1) {
				w.nonSerializedParameter((org.apache.dubbo.rpc.protocol.dubbo.support.NonSerialized) $4[0]);
				return null;
			}
			if ("returnNonSerialized".equals($2) && $3.length == 0) {
				return ($w) w.returnNonSerialized();
			}
			if ("getThreadName".equals($2) && $3.length == 0) {
				return ($w) w.getThreadName();
			}
			if ("gerPerson".equals($2) && $3.length == 1) {
				return ($w) w.gerPerson((org.apache.dubbo.rpc.protocol.dubbo.support.Person) $4[0]);
			}
			if ("getbyte".equals($2) && $3.length == 1) {
				return ($w) w.getbyte(((Byte) $4[0]).byteValue());
			}
			if ("getPerson".equals($2) && $3.length == 1
					&& $3[0].getName().equals("org.apache.dubbo.rpc.protocol.dubbo.support.Man")) {
				return ($w) w.getPerson((org.apache.dubbo.rpc.protocol.dubbo.support.Man) $4[0]);
			}
			if ("getPerson".equals($2) && $3.length == 2
					&& $3[0].getName().equals("org.apache.dubbo.rpc.protocol.dubbo.support.Person")
					&& $3[1].getName().equals("org.apache.dubbo.rpc.protocol.dubbo.support.Person")) {
				return ($w) w.getPerson((org.apache.dubbo.rpc.protocol.dubbo.support.Person) $4[0],
						(org.apache.dubbo.rpc.protocol.dubbo.support.Person) $4[1]);
			}
			if ("getPerson".equals($2) && $3.length == 1
					&& $3[0].getName().equals("org.apache.dubbo.rpc.protocol.dubbo.support.Person")) {
				return ($w) w.getPerson((org.apache.dubbo.rpc.protocol.dubbo.support.Person) $4[0]);
			}
		} catch (Throwable e) {
			throw new java.lang.reflect.InvocationTargetException(e);
		}
		throw new org.apache.dubbo.common.bytecode.NoSuchMethodException("Not found method \"" + $2
				+ "\" in class org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl.");
	}

	public void setPropertyValue(Object o, String n, Object v) {
		org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl w;
		try {
			w = ((org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl) $1);
		} catch (Throwable e) {
			throw new IllegalArgumentException(e);
		}
		throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2
				+ "\" field or setter method in class org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl.");
	}

	public Object getPropertyValue(Object o, String n) {
		org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl w;
		try {
			w = ((org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl) $1);
		} catch (Throwable e) {
			throw new IllegalArgumentException(e);
		}
		if ($2.equals("threadName")) {
			return ($w) w.getThreadName();
		}
		throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2
				+ "\" field or setter method in class org.apache.dubbo.rpc.protocol.dubbo.support.DemoServiceImpl.");
	}

}
