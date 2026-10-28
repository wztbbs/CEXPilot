package com.cexpilot.monitor;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.model.CreateEmailOptions;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import static com.cexpilot.monitor.MonitorModels.*;

/** 真实模式SENT表示Resend接受发送；模拟模式SENT带dry-run标记。均不证明邮件送达。 */
@Component
public class EmailNotificationSender {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(EmailNotificationSender.class);
    public record Outcome(String status,String messageId,String error,boolean uncertain) {
        public Outcome(String status,String messageId,String error) { this(status,messageId,error,false); }
    }
    private final ResendConfig config;
    private final MonitorConfig monitor;
    private final Clock clock;
    private final Resend resend;
    @org.springframework.beans.factory.annotation.Autowired
    public EmailNotificationSender(ResendConfig config,MonitorConfig monitor,Clock clock) {
        this(config,monitor,clock,client(config));
    }
    private static Resend client(ResendConfig config) {
        if(config.isDryRun() || !config.isConfigured()) return null;
        var builder=Resend.builder().apiKey(config.getApiKey())
                .connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(20)).callTimeout(Duration.ofSeconds(30));
        if(!config.getBaseUrl().isBlank()) builder.baseUrl(config.getBaseUrl());
        return builder.build();
    }
    EmailNotificationSender(ResendConfig config,MonitorConfig monitor,Clock clock,Resend resend) {
        this.config=config; this.monitor=monitor; this.clock=clock; this.resend=resend;
    }
    public boolean isAvailable() {
        return (config.isDryRun() || config.isConfigured() && resend!=null) && MonitorEmailAddress.valid(monitor.getMailFrom());
    }
    public boolean isDryRun() { return config.isDryRun(); }
    public Outcome send(Delivery delivery) {
        if(!isAvailable()) return new Outcome("RETRY",null,"尚未配置有效的 Resend API Key 和发件人");
        if(delivery.createdAt()==null || !clock.instant().isBefore(delivery.createdAt().plus(Duration.ofHours(23))))
            return new Outcome("UNKNOWN",null,"超过安全重试窗口，不再提交相同邮件",true);
        var request=delivery.snapshot().path("resend_request");
        if(!MonitorEmailAddress.valid(request.path("from").asText()) || !request.path("to").isArray() || request.path("to").size()!=1
                || !delivery.recipient().equals(request.at("/to/0").asText()) || !request.path("subject").isTextual() || !request.path("text").isTextual()
                || !delivery.eventId().equals(request.at("/headers/X-CEXPilot-Event").asText()))
            return new Outcome("UNKNOWN",null,"缺少一致的冻结邮件请求，未发送",true);
        if(config.isDryRun() || config.isDryRunRecipient(delivery.recipient())) {
            log.info("邮件模拟发送成功 deliveryId={} eventId={} mode={}，不发送真实邮件",delivery.deliveryId(),delivery.eventId(),
                    config.isDryRun()?"DRY_RUN":"RECIPIENT_DRY_RUN");
            return new Outcome("SENT","dry-run:"+delivery.deliveryId(),null);
        }
        var params=CreateEmailOptions.builder().from(request.path("from").asText()).to(delivery.recipient())
                .subject(request.path("subject").asText()).text(request.path("text").asText())
                .headers(Map.of("X-CEXPilot-Event",delivery.eventId())).build();
        var options=RequestOptions.builder().setIdempotencyKey("cexpilot/"+delivery.deliveryId()).build();
        try {
            var response=resend.emails().send(params,options);
            if(response==null || response.getId()==null || response.getId().isBlank())
                return new Outcome("RETRY",null,"Resend 响应缺少邮件 ID",true);
            return new Outcome("SENT",response.getId(),null);
        } catch(ResendException error) {
            Integer code=error.getStatusCode(); String name=error.getErrorName();
            // 不保存异常 message/原始响应，避免服务商回显邮箱、密钥或正文。
            String detail="Resend "+(code==null?"连接或响应异常":("HTTP "+code))
                    +(name!=null && name.matches("[a-z_]{1,80}")?" / "+name:"");
            if(code==null || code>=500 || code==408 || "concurrent_idempotent_requests".equals(name))
                return new Outcome("RETRY",null,detail,true);
            if(code==429) return new Outcome("RETRY",null,detail);
            if("invalid_idempotent_request".equals(name) || code==409) return new Outcome("UNKNOWN",null,detail,true);
            return new Outcome("FAILED",null,detail);
        } catch(RuntimeException error) {
            return new Outcome("RETRY",null,"Resend 调用结果不确定",true);
        }
    }
    static String body(Delivery delivery) {
        var saved=delivery.snapshot().path("resend_request").path("text");
        return saved.isTextual()?saved.asText():MonitorEmailMessage.body(delivery.snapshot(),delivery.eventId());
    }
}
