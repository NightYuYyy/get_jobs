package com.getjobs.worker.boss;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 独立于浏览器的回复边界校验。 */
public final class ReplyPolicy {
    private ReplyPolicy() {}
    private static final Pattern REVIEW = Pattern.compile(
            "薪|待遇|工资|offer|面试|时间|入职|电话|手机|微信|邮箱|地址|身份证|验证码|付款|转账|出差|加班|合同|链接|到岗|承诺|保证|接受|同意",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(https?://|sk-[a-z0-9_-]{10,}|-----BEGIN|(?<!\\d)1[3-9]\\d{9}(?!\\d)|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,})");

    public static String messageKey(String conversation, String messageId) {
        if (conversation == null || conversation.isBlank() || messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("缺少会话或消息 ID，不能安全去重");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((conversation + "\n" + messageId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean needsReview(String text) {
        return text == null || text.isBlank() || REVIEW.matcher(text).find() || SECRET.matcher(text).find();
    }

    public static boolean canAutoReply(String question, String answer) {
        return !needsReview(question) && !needsReview(answer) && !Pattern.compile("[0-9]").matcher(answer).find()
                && question.replaceAll("[\\s，。！？,.!?]", "").matches("你好|您好|在吗|还在找工作吗|还在看机会吗|有兴趣了解一下吗");
    }

    public static String validateReply(String text) {
        if (text == null) throw new IllegalArgumentException("AI 返回空内容");
        String value = text.trim();
        if (value.isEmpty() || value.length() > 300 || value.startsWith("{") || value.startsWith("[")
                || value.contains(String.valueOf((char) 96).repeat(3)) || value.equalsIgnoreCase("false")
                || SECRET.matcher(value).find()
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new IllegalArgumentException("回复不是可发送的短文本，或包含联系方式、链接等需人工处理的内容");
        }
        return value;
    }
}
