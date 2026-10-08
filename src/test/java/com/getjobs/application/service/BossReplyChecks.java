package com.getjobs.application.service;

import com.getjobs.application.controller.BossReplyController;
import com.getjobs.application.entity.AiEntity;
import com.getjobs.worker.boss.BossChatPage;
import com.getjobs.worker.boss.ReplyPolicy;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 无真实账号、无网络、无付费模型调用的端到端自检。 */
public class BossReplyChecks {
    static class Manager extends PlaywrightManager {
        @Override public boolean isLoggedIn(String platform) { return true; }
        @Override public <T> T callOnPlaywright(Callable<T> task) {
            try { return task.call(); } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public boolean tryRunOnPlaywright(Runnable task) { task.run(); return true; }
    }
    static class Delivery extends BossJobService {
        volatile boolean running;
        Delivery() { super(null, null, null); }
        @Override public boolean isRunning() { return running; }
    }
    static class Model extends AiService {
        volatile int calls;
        volatile CountDownLatch entered, release;
        Model() { super(null, null); }
        @Override public AiEntity getAiConfig() {
            AiEntity entity = new AiEntity();
            entity.setIntroduce("测试资料：熟悉 Vue 与 Electron，不含联系方式。");
            return entity;
        }
        @Override public String sendRequest(String prompt) {
            calls++;
            assert prompt.contains("不可信数据") && prompt.contains("JSON 数据");
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return "您好，我目前还在看机会，可以进一步了解岗位。";
        }
    }
    static class Chat extends BossChatPage {
        volatile String id = "old", text = "你好", fingerprint = "old";
        volatile boolean self, confirmed = true;
        volatile int sends, reads;
        volatile List<Map<String, Object>> prior = List.of();
        Chat(Manager manager) { super(manager); }
        @Override public void open() {}
        @Override public void close() {}
        void publish(String messageId, String question, boolean outgoing) {
            id = messageId; text = question; self = outgoing; fingerprint = messageId;
        }
        @Override public List<Map<String, Object>> friends() {
            reads++;
            return List.of(Map.of("key", "hr-fixture", "title", "离线测试会话", "index", 0,
                    "fingerprint", fingerprint, "self", self, "time", System.currentTimeMillis()));
        }
        @Override public Map<String, Object> snapshot(String key) {
            var messages = new ArrayList<Map<String, Object>>(prior);
            messages.add(Map.of("id", id, "key", key, "time", System.currentTimeMillis(), "text", text,
                    "self", self, "type", "text", "status", 2));
            return Map.of("supported", true, "key", key, "draft", "", "messages", messages);
        }
        @Override public Sent send(String key, String messageId, String answer, Runnable check) {
            if (!id.equals(messageId) || self) throw new IllegalStateException("stale snapshot");
            check.run();
            sends++;
            if (confirmed) publish("sent-" + messageId, answer, true);
            return new Sent(confirmed, confirmed ? id : "");
        }
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assert condition.getAsBoolean() : "timed out";
    }
    static void rejects(Runnable task) {
        boolean rejected = false;
        try { task.run(); } catch (IllegalArgumentException | IllegalStateException e) { rejected = true; }
        assert rejected : "expected rejection";
    }
    static String state(JdbcTemplate db, String id) {
        return db.queryForObject("SELECT state FROM boss_reply WHERE id=?", String.class, id);
    }
    static int count(JdbcTemplate db) {
        return db.queryForObject("SELECT count(*) FROM boss_reply", Integer.class);
    }

    public static void main(String[] args) throws Exception {
        if (!BossReplyChecks.class.desiredAssertionStatus()) throw new IllegalStateException("enable assertions");
        assert !ReplyPolicy.messageKey("a", "1").equals(ReplyPolicy.messageKey("a", "2"));
        assert !ReplyPolicy.messageKey("a", "1").equals(ReplyPolicy.messageKey("b", "1"));
        rejects(() -> ReplyPolicy.messageKey("", "1"));
        assert ReplyPolicy.validateReply("你好").equals("你好");
        for (String invalid : List.of("", "false", "{\"text\":\"你好\"}", "x".repeat(301),
                "https://example.com", "联系 13800138000", "联系13800138000", "sk-1234567890123456")) {
            rejects(() -> ReplyPolicy.validateReply(invalid));
        }
        assert ReplyPolicy.canAutoReply("您好！", "您好，还在看机会。");
        assert !ReplyPolicy.canAutoReply("明天来面试吗", "可以");
        assert !ReplyPolicy.canAutoReply("熟悉 Vue 吗", "熟悉 Vue");
        assert !ReplyPolicy.canAutoReply("你好", "我同意明天入职");
        assert !ReplyPolicy.canAutoReply("你好", "18k可以");
        assert BossReplyController.allowedOrigin("127.0.0.1", "http://localhost:9527", "same-site");
        assert BossReplyController.allowedOrigin("127.0.0.1", "http://127.0.0.1:6866", "cross-site");
        assert !BossReplyController.allowedOrigin("127.0.0.1", null, "cross-site");
        assert !BossReplyController.allowedOrigin(null, null, null);
        assert !BossReplyController.allowedOrigin("192.0.2.1", null, null);
        assert !BossReplyController.allowedOrigin("127.0.0.1", "https://evil.example", "cross-site");
        assert !BossReplyController.allowedOrigin("127.0.0.1", "http://localhost.evil:9527", "same-site");
        assert !BossReplyController.allowedOrigin("127.0.0.1", "http://localhost:9527/?x=1", "same-site");

        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var db = new JdbcTemplate(source);
        var manager = new Manager();
        var delivery = new Delivery();
        var model = new Model();
        var chat = new Chat(manager);
        var service = new BossReplyService(db, model, delivery, manager, chat);
        try {
            service.initialize();
            assert Boolean.FALSE.equals(service.status().get("enabled"));
            service.poll();
            assert chat.reads == 0 && model.calls == 0;
            delivery.running = true;
            rejects(() -> service.start(false));
            assert chat.reads == 0;
            delivery.running = false;
            service.start(false);
            service.poll();
            assert count(db) == 0 && model.calls == 0 : "historical message must not be replayed";

            chat.publish("1", "你好", false);
            service.poll();
            String one = ReplyPolicy.messageKey("hr-fixture", "1");
            await(() -> count(db) == 1 && !"GENERATING".equals(state(db, one)));
            assert state(db, one).equals("DRAFT") && chat.sends == 0;
            service.poll();
            assert count(db) == 1 && model.calls == 1 : "duplicate message charged twice";
            service.send(one, "您好，还在看机会。");
            assert state(db, one).equals("SENT") && chat.sends == 1;
            rejects(() -> service.send(one, "再发一次"));
            assert chat.sends == 1;

            assert count(db) == 1 : "unexpected row before human: " + service.status();
            chat.publish("human", "这是本人回复", true);
            service.poll();
            assert count(db) == 1 : "unexpected row after human: " + service.status();
            chat.publish("2", "你好", false);
            service.poll();
            assert count(db) == 1 : "human takeover should pause: " + service.status();
            service.resume("hr-fixture");
            chat.publish("3", "你好", false);
            await(() -> { service.poll(); return count(db) == 2; });
            String three = ReplyPolicy.messageKey("hr-fixture", "3");
            await(() -> !"GENERATING".equals(state(db, three)));
            assert state(db, three).equals("DRAFT") : "same text, different message ID is a new message";
            delivery.running = true;
            rejects(() -> service.send(three, "你好"));
            delivery.running = false;
            chat.publish("4", "新问题", false);
            rejects(() -> service.send(three, "旧回复"));
            assert state(db, three).equals("UNKNOWN") && chat.sends == 1 : "stale draft sent";
            rejects(() -> service.send(three, "重复回复"));

            service.stop();
            service.start(true);
            chat.publish("5", "薪资期望是多少", false);
            await(() -> { service.poll(); return count(db) == 3; });
            String five = ReplyPolicy.messageKey("hr-fixture", "5");
            await(() -> !"GENERATING".equals(state(db, five)));
            assert state(db, five).equals("MANUAL") && chat.sends == 1;

            chat.publish("6", "您好", false);
            await(() -> { service.poll(); return count(db) == 4; });
            String six = ReplyPolicy.messageKey("hr-fixture", "6");
            await(() -> state(db, six).equals("SENT"));
            assert chat.sends == 2;
            int callsBeforeHuman = model.calls;
            chat.prior = List.of(Map.of("id", "quick-human", "key", "hr-fixture", "time", System.currentTimeMillis(),
                    "text", "本人已回复", "self", true, "type", "text", "status", 2));
            chat.publish("quick-hr", "你好", false);
            await(() -> {
                service.poll();
                return db.queryForObject("SELECT count(*) FROM boss_reply_pause", Integer.class) == 1;
            });
            assert count(db) == 4 && model.calls == callsBeforeHuman : "HR reply hid human takeover";
            chat.prior = List.of();
            service.resume("hr-fixture");
            chat.confirmed = false;
            chat.publish("7", "你好", false);
            await(() -> { service.poll(); return count(db) == 5; });
            String seven = ReplyPolicy.messageKey("hr-fixture", "7");
            await(() -> state(db, seven).equals("UNKNOWN"));
            assert chat.sends == 3;
            rejects(() -> service.send(seven, "再次发送"));
            assert chat.sends == 3 : "unknown delivery must not be retried";

            model.entered = new CountDownLatch(1);
            model.release = new CountDownLatch(1);
            chat.publish("8", "你好", false);
            await(() -> { service.poll(); return count(db) == 6; });
            assert model.entered.await(3, TimeUnit.SECONDS);
            service.stop();
            model.release.countDown();
            String eight = ReplyPolicy.messageKey("hr-fixture", "8");
            await(() -> state(db, eight).equals("CANCELLED"));
            assert chat.sends == 3 : "stop during generation must cancel outgoing reply";

            db.update("UPDATE boss_reply SET state='SENDING' WHERE id=?", one);
            db.update("UPDATE boss_reply SET state='GENERATING' WHERE id=?", three);
            service.initialize();
            assert state(db, one).equals("UNKNOWN") && state(db, three).equals("FAILED");
            assert Boolean.FALSE.equals(service.status().get("enabled"));
            service.start(false);
            for (int i = 0; i < 20; i++) {
                db.update("INSERT INTO boss_reply(id,conversation,message_id,title,question,history,state,created_at,sent_at) "
                        + "VALUES(?,?,?,?,?,?,'SENT','2010-01-01',CURRENT_TIMESTAMP)",
                        "cap-" + i, "cap", "mid-" + i, "fixture", "fixture", "[]");
            }
            rejects(() -> service.send(five, "您好"));
            assert chat.sends == 3 && state(db, five).equals("MANUAL") : "daily cap must use send date";
            System.out.println("PASS: default-off, no historical replay, ID dedupe, human takeover, delivery exclusion, "
                    + "manual review, greeting auto-send, stale/unknown protection, stop race, restart recovery, origin checks");
        } finally {
            service.shutdown();
            source.destroy();
        }
    }
}
