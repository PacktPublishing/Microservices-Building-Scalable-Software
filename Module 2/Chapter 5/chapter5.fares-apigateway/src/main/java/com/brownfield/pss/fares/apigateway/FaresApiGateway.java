package com.brownfield.pss.fares.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;

import springfox.documentation.swagger2.annotations.EnableSwagger2;

@EnableZuulProxy
@EnableDiscoveryClient
@SpringBootApplication
@EnableSwagger2 
public class FaresApiGateway {

	public static void main(String[] args) {
		SpringApplication.run(FaresApiGateway.class, args);
	}
}
