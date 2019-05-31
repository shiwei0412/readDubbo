package org.apache.dubbo.demo.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DubboMain {
    private static final Logger logger = LoggerFactory.getLogger(DubboMain.class);

    public static void main(String[] args){
        org.apache.dubbo.container.Main.main(new String[]{"spring","log4j"});
    }
}