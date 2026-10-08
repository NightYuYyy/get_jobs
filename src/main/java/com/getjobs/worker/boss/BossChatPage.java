package com.getjobs.worker.boss;

import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 只在 Playwright 专用线程内访问独立聊天标签页，不修改投递页。 */
@Component
@RequiredArgsConstructor
public class BossChatPage {
    private final PlaywrightManager manager;
    private Page page;
    private final String reader = loadReader();

    private static String loadReader() {
        try (var in = BossChatPage.class.getResourceAsStream("/boss-reply-reader.js")) {
            if (in == null) throw new IllegalStateException("缺少聊天读取脚本");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载聊天读取脚本", e);
        }
    }

    public void open() {
        if (page == null || page.isClosed()) {
            page = manager.getContext().newPage();
            page.setDefaultTimeout(5000);
            page.navigate("https://www.zhipin.com/web/geek/chat",
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15000));
        }
        page.waitForSelector(".friend-content", new Page.WaitForSelectorOptions().setTimeout(10000));
        if (friends().isEmpty()) throw new IllegalStateException("没有可识别的普通 HR 会话，或 BOSS 页面结构已变化");
    }

    public void close() {
        if (page != null && !page.isClosed()) page.close();
        page = null;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> friends() {
        if (page == null || page.isClosed() || !page.url().startsWith("https://www.zhipin.com/web/geek/chat")) {
            throw new IllegalStateException("回复标签页已关闭或离开 BOSS 消息页");
        }
        return (List<Map<String, Object>>) page.evaluate(reader, Map.of("mode", "friends"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot(String key) {
        var friend = friends().stream().filter(f -> key.equals(f.get("key"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("目标会话不在当前已加载列表中"));
        page.locator(".friend-content").nth(((Number) friend.get("index")).intValue()).click();
        page.waitForFunction("(key) => { const el=document.querySelector('.friend-content.selected'); "
                + "for(let n=el,i=0;n&&i<6;n=n.parentElement,i++){const v=n.__vue__;const b=v&&(v.boss||v.$props?.boss);"
                + "if(b?.uniqueId)return String(b.uniqueId)===key;}return false;}", key);
        page.waitForSelector(".chat-conversation ul.im-list .message-item");
        var result = (Map<String, Object>) page.evaluate(reader, Map.of("mode", "conversation"));
        if (!Boolean.TRUE.equals(result.get("supported")) || !key.equals(result.get("key"))) {
            throw new IllegalStateException("聊天结构无法可靠识别，已停止处理");
        }
        return result;
    }

    public record Sent(boolean confirmed, String messageId) {}

    @SuppressWarnings("unchecked")
    public Sent send(String key, String messageId, String text, Runnable checkAllowed) {
        var current = snapshot(key);
        var last = last(current);
        if (!messageId.equals(last.get("id")) || !Boolean.FALSE.equals(last.get("self"))
                || !"text".equals(last.get("type")) || !String.valueOf(current.get("draft")).isBlank()) {
            throw new IllegalStateException("会话已变化、已有人工回复或输入草稿，不能发送旧回复");
        }
        checkAllowed.run();
        var input = page.locator("#chat-input[contenteditable='true']");
        input.fill(text);
        page.waitForFunction("text => {const b=document.querySelector(\"button[type='send'].btn-send\");"
                + "const i=document.querySelector('#chat-input');return b&&!b.classList.contains('disabled')"
                + "&&i&&(i.innerText||'').trim()===text;}", text);
        var beforeClick = (Map<String, Object>) page.evaluate(reader, Map.of("mode", "conversation"));
        if (!Boolean.TRUE.equals(beforeClick.get("supported")) || !key.equals(beforeClick.get("key"))
                || !messageId.equals(last(beforeClick).get("id")) || !text.equals(beforeClick.get("draft"))) {
            throw new IllegalStateException("发送前会话或输入内容发生变化，请人工检查");
        }
        var button = page.locator("button[type='send'].btn-send");
        if (button.count() != 1 || button.getAttribute("class").contains("disabled")) {
            throw new IllegalStateException("没有唯一且可用的发送按钮；请检查浏览器中的草稿");
        }
        checkAllowed.run();
        button.click();
        for (int i = 0; i < 10; i++) {
            page.waitForTimeout(300);
            var result = (Map<String, Object>) page.evaluate(reader, Map.of("mode", "conversation"));
            if (!Boolean.TRUE.equals(result.get("supported")) || !key.equals(result.get("key"))) return new Sent(false, "");
            var tail = last(result);
            boolean acknowledged = tail.get("status") instanceof Number number && (number.intValue() == 2 || number.intValue() == 3);
            if (Boolean.TRUE.equals(tail.get("self")) && text.equals(tail.get("text"))
                    && !messageId.equals(tail.get("id")) && acknowledged) return new Sent(true, (String) tail.get("id"));
        }
        return new Sent(false, "");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> last(Map<String, Object> snapshot) {
        var messages = (List<Map<String, Object>>) snapshot.get("messages");
        if (messages == null || messages.isEmpty()) throw new IllegalStateException("没有可确认的消息");
        return messages.getLast();
    }
}
