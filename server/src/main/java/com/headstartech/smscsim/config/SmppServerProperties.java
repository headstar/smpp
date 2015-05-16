package com.headstartech.smscsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.validation.constraints.NotNull;

/**
 * Created by per on 5/16/15.
 */
@Component
@ConfigurationProperties(prefix="smppserver")
public class SmppServerProperties {

    @NotNull
    private Integer port;

    @NotNull
    private Integer maxConnections;

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(Integer maxConnections) {
        this.maxConnections = maxConnections;
    }
}
