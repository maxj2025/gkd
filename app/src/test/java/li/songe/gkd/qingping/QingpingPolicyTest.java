package li.songe.gkd.qingping;

import org.junit.Test;
import static org.junit.Assert.*;

public class QingpingPolicyTest {
    @Test public void onlySplashAndExplicitAdExitRulesAreAllowed() {
        assertEquals("splash", QingpingPolicy.kind("开屏广告-跳过"));
        assertEquals("exit", QingpingPolicy.kind("广告跳转-关闭落地页"));
        assertEquals("other", QingpingPolicy.kind("自动确认登录"));
        assertEquals("other", QingpingPolicy.kind("局部广告-自动点赞"));
        assertEquals("other", QingpingPolicy.kind(null));
    }
    @Test public void startupWindowHasHardBoundaryAndStopsWhenScreenOff() {
        assertTrue(QingpingPolicy.allows("splash", false, true, 15000, true));
        assertFalse(QingpingPolicy.allows("splash", false, true, 15001, true));
        assertFalse(QingpingPolicy.allows("splash", false, true, -1, true));
        assertFalse(QingpingPolicy.allows("splash", false, false, 10, true));
        assertFalse(QingpingPolicy.allows("other", true, true, 10, true));
    }
    @Test public void exitRequiresOptInAndAppSpecificRule() {
        assertFalse(QingpingPolicy.allows("exit", false, true, 10, true));
        assertFalse(QingpingPolicy.allows("exit", true, true, 10, false));
        assertTrue(QingpingPolicy.allows("exit", true, true, 60000, true));
    }
    @Test public void failedAndNoOpActionsAreNeverCounted() {
        assertFalse(QingpingPolicy.shouldCount(false, "click"));
        assertFalse(QingpingPolicy.shouldCount(true, "none"));
        assertFalse(QingpingPolicy.shouldCount(true, null));
        assertTrue(QingpingPolicy.shouldCount(true, "click"));
        assertTrue(QingpingPolicy.shouldCount(true, "back"));
    }
    @Test public void idleContentEventsAreDroppedWithoutLosingAppTransitions() {
        assertTrue(QingpingPolicy.shouldDropContent(true, 15001, false));
        assertFalse(QingpingPolicy.shouldDropContent(true, 15000, false));
        assertFalse(QingpingPolicy.shouldDropContent(false, 60000, false));
        assertFalse(QingpingPolicy.shouldDropContent(true, 60000, true));
    }
}
