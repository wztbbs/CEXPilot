package com.cexpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "exchange")
public class ExchangeConfig {

    private final Binance binance = new Binance();
    private final Okx okx = new Okx();
    private final Ethereum ethereum = new Ethereum();

    public Binance getBinance() {
        return binance;
    }

    public Okx getOkx() {
        return okx;
    }

    public Ethereum getEthereum() {
        return ethereum;
    }

    public static class Binance {
        /** USDⓈ-M 永续合约公共行情入口，资金费率 / OI / 标记价格都在这里。 */
        private String baseUrl = "https://fapi.binance.com";
        private String apiKey = "";
        private String secretKey = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }

    public static class Okx {
        private String baseUrl = "https://www.okx.com";
        private String apiKey = "";
        private String secretKey = "";
        private String passphrase = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getPassphrase() {
            return passphrase;
        }

        public void setPassphrase(String passphrase) {
            this.passphrase = passphrase;
        }
    }

    public static class Ethereum {
        private String rpcUrl = "https://ethereum-rpc.publicnode.com";

        public String getRpcUrl() {
            return rpcUrl;
        }

        public void setRpcUrl(String rpcUrl) {
            this.rpcUrl = rpcUrl;
        }
    }
}
