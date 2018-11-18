package me.itzg.tryetcdworkpart.config;

import com.coreos.jetcd.Client;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EtcdConfig {

  private final EtcdProperties properties;

  @Autowired
  public EtcdConfig(EtcdProperties properties) {
    this.properties = properties;
  }

  @Bean
  public Client etcdClient() {
    return Client.builder().endpoints(properties.getUrl()).build();
  }
}
