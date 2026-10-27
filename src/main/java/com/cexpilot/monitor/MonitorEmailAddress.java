package com.cexpilot.monitor;

import java.util.regex.Pattern;

/** 灰度入口只接受单个普通邮箱地址，不接受展示名、地址列表或邮件头。无需邮件协议依赖。 */
final class MonitorEmailAddress {
    private static final Pattern ADDRESS=Pattern.compile(
            "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
            +"(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+"
            +"[A-Za-z]{2,63}");
    private MonitorEmailAddress() {}
    static boolean valid(String value) {
        return value!=null && value.length()<=254 && value.indexOf('@')<=64 && ADDRESS.matcher(value).matches();
    }
}
