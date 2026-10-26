package li.songe.gkd.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.songe.gkd.META
import li.songe.gkd.qingping.QingpingPolicy
import li.songe.gkd.qingping.QingpingEventBuffer
import li.songe.gkd.qingping.QingpingQueryEvents
import li.songe.gkd.qingping.QingpingReadGate
import li.songe.gkd.qingping.QingpingPollingRule
import li.songe.gkd.qingping.qingpingFollowupDelay
import li.songe.gkd.qingping.QingpingQueryRunner
import li.songe.gkd.qingping.QingpingFollowup
import li.songe.gkd.qingping.QingpingOutcome
import li.songe.gkd.qingping.QingpingOutcomePolicy
import li.songe.gkd.qingping.qingpingReadableTree
import li.songe.gkd.db.DbSet
import li.songe.gkd.util.LogUtils
import kotlinx.coroutines.CancellationException
import li.songe.gkd.data.ActionPerformer
import li.songe.gkd.data.ActionResult
import li.songe.gkd.data.AppRule
import li.songe.gkd.data.GkdAction
import li.songe.gkd.data.ResolvedRule
import li.songe.gkd.data.RpcError
import li.songe.gkd.data.RuleStatus
import li.songe.gkd.isActivityVisible
import li.songe.gkd.service.A11yService
import li.songe.gkd.service.EventService
import li.songe.gkd.service.topAppIdFlow
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.shizuku.uiAutomationFlow
import li.songe.gkd.store.actualBlockA11yAppList
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.AutomatorModeOption
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.runMainPost
import li.songe.gkd.util.showActionToast
import li.songe.gkd.util.systemUiAppId
import li.songe.gkd.util.ruleSummaryFlow
import li.songe.selector.MatchOption
import li.songe.selector.Selector
import java.util.concurrent.Executors


private val eventDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
private val queryDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
private val actionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

private val windowReadGate = QingpingReadGate()
private val latestServiceMode = atomic(0)
private val latestServiceTime = atomic(0L)

class A11yRuleEngine(val service: A11yCommonImpl) {
    private val a11yContext = A11yContext(this)
    private var connectedTime = 0L
    private val effective get() = connectedTime != 0L && latestServiceTime.value == connectedTime &&
        latestServiceMode.value == service.mode.value
    private val hasOthersService = when (service.mode) {
        AutomatorModeOption.A11yMode -> uiAutomationFlow.value != null
        AutomatorModeOption.AutomationMode -> A11yService.instance != null
    }
    private var summaryJob: Job? = null

    fun onA11yConnected() {
        val serviceTime = System.currentTimeMillis()
        connectedTime = serviceTime
        latestServiceMode.value = service.mode.value
        latestServiceTime.value = serviceTime
        // Start rule resolution without opening the UI. A foreground event may arrive
        // before subscriptions resolve; refresh that same page when they become ready.
        summaryJob?.cancel()
        summaryJob = scope.launch(eventDispatcher) {
            ruleSummaryFlow.collect {
                if (!effective) return@collect
                synchronized(topActivityFlow) {
                    val top = topActivityFlow.value
                    if (top.appId.isNotEmpty()) updateTopActivity(top.appId, top.activityId)
                }
                startQueryJob()
            }
        }
        scope.launch {
            storeFlow.map { it.enableMatch }.distinctUntilChanged().collect { enabled ->
                if (!enabled) stopMatching() else startQueryJob()
            }
        }
        scope.launch(eventDispatcher) {
            // A service restored after the page opened missed its original event.
            // Bootstrap only an unknown foreground, with a finite read budget.
            repeat(3) {
                if (!effective || !isInteractive || topActivityFlow.value.appId.isNotEmpty()) return@launch
                val appId = getTimeoutActiveWindow()?.packageName?.toString()
                if (!appId.isNullOrEmpty()) {
                    synchronized(topActivityFlow) {
                        if (topActivityFlow.value.appId.isEmpty()) updateTopActivity(appId, null)
                    }
                    startQueryJob()
                    return@launch
                }
                delay(200)
            }
        }
        if (storeFlow.value.enableBlockA11yAppList && !actualBlockA11yAppList.contains(topAppIdFlow.value)) {
            startQueryJob(byForced = true)
        }
        runMainPost(1000L) {// 共存 1000ms, 等待另一个服务稳定
            if (latestServiceTime.value == serviceTime) {
                when (service.mode) {
                    AutomatorModeOption.A11yMode -> uiAutomationFlow.value?.shutdown(true)
                    AutomatorModeOption.AutomationMode -> A11yService.instance?.shutdown(true)
                }
            }
        }
    }

    fun onScreenForcedActive() {
        // 关闭屏幕 -> Activity::onStop -> 点亮屏幕 -> Activity::onStart -> Activity::onResume
        val a = topActivityFlow.value
        synchronized(topActivityFlow) {
            updateTopActivity(
                a.appId,
                a.activityId,
                scene = ActivityScene.ScreenOn
            )
        }
        startQueryJob()
    }

    val safeActiveWindow: AccessibilityNodeInfo?
        get() = windowReadGate.tryRead { readActiveWindow() }

    private fun readActiveWindow(): AccessibilityNodeInfo? = try {
            // 某些应用耗时 554ms
            // java.lang.SecurityException: Call from user 0 as user -2 without permission INTERACT_ACROSS_USERS or INTERACT_ACROSS_USERS_FULL not allowed.
            service.windowNodeInfo?.setGeneratedTime()
        } catch (_: Throwable) {
            null
        }

    val safeActiveWindowAppId: String?
        get() = safeActiveWindow?.packageName?.toString()

    private val scope get() = service.scope

    @Volatile
    private var latestStateEvent: A11yEvent? = null
    private var lastContentEventTime = 0L
    private var lastEventTime = 0L
    private val eventDeque = QingpingEventBuffer<A11yEvent>(64,
        same = { a, b -> a.sameAs(b) }, priority = { it.type == STATE_CHANGED })
    private val eventRunner = QingpingQueryRunner()
    fun onA11yEvent(event: AccessibilityEvent?) {
        if (!effective || !isInteractive) return
        if (!event.isUseful()) return
        // 拒绝副屏无障碍事件
        if (AndroidTarget.TIRAMISU && event.displayId != Display.DEFAULT_DISPLAY) return
        onA11yFeatEvent(event)
        if (event.eventType == CONTENT_CHANGED) {
            if (!isInteractive) return // 屏幕关闭后仍然有无障碍事件 type:2048, time:8094, app:com.miui.aod, cls:android.widget.TextView
            val currentAppId = topActivityFlow.value.appId
            if (QingpingPolicy.shouldDropContent(
                    event.packageName?.toString() == currentAppId,
                    System.currentTimeMillis() - appChangeTime,
                    storeFlow.value.qingpingExitApps.contains(currentAppId)
                )) return
            if (event.packageName == systemUiAppId && event.packageName != topActivityFlow.value.appId) return
        }
        // 过滤部分输入法事件
        if (event.packageName == imeAppId && topActivityFlow.value.appId != imeAppId) {
            if (event.recordCount == 0 && event.action == 0 && !event.isFullScreen) return
        }
        // 直接丢弃自身事件，自行更新 topActivity
        if ((event.eventType == CONTENT_CHANGED || !isActivityVisible) && event.packageName == META.appId) return

        val a11yEvent = event.toA11yEvent() ?: return
        if (a11yEvent.type == CONTENT_CHANGED) {
            // 防止 content 类型事件过快
            if (a11yEvent.time - lastContentEventTime < 100 && a11yEvent.time - appChangeTime > 5000 && a11yEvent.time - lastTriggerTime > 3000) {
                return
            }
            lastContentEventTime = a11yEvent.time
        }
        EventService.logEvent(event)
        if (META.debuggable) {
            Log.d(
                "onNewA11yEvent",
                "type:${event.eventType}, time:${event.eventTime - lastEventTime}, app:${event.packageName}, cls:${event.className}"
            )
        }
        if (event.eventTime < lastEventTime && event.eventType != STATE_CHANGED) {
            // Content events can be stale; state changes still need a fresh window check.
            // Android may deliver a valid launch state after newer content events.
            return
        }
        lastEventTime = maxOf(lastEventTime, event.eventTime)
        if (event.eventType == STATE_CHANGED) {
            latestStateEvent = a11yEvent
        }
        eventDeque.offer(a11yEvent)
        startEventConsumer()
    }

    private val queryEvents = QingpingQueryEvents<A11yEvent> { a, b -> a.sameAs(b) }

    private fun startEventConsumer() {
        if (!effective || !isInteractive) return
        eventRunner.tryStart(scope, eventDispatcher, onComplete = {
            if (eventDeque.hasPending() && effective && isInteractive) startEventConsumer()
        }) {
            while (currentCoroutineContext().isActive && effective && isInteractive) {
                val batch = eventDeque.takeBatch()
                if (batch.isEmpty()) break
                try { consumeEvent(batch) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LogUtils.d(e) }
            }
        }
    }

    private suspend fun consumeEvent(consumedEvents: List<A11yEvent>) {
        val latestEvent = consumedEvents.last()
        val evAppId = latestEvent.appId
        val evActivityId = latestEvent.name
        val oldAppId = topActivityFlow.value.appId
        val rightAppId = if (oldAppId == evAppId) {
            evAppId
        } else {
            getTimeoutAppId(fresh = latestEvent.type == STATE_CHANGED) ?: return
        }
        if (rightAppId == evAppId) {
            if (latestEvent.type == STATE_CHANGED) {
                synchronized(topActivityFlow) {
                    // tv.danmaku.bili, com.miui.home, com.miui.home.launcher.Launcher
                    if (isActivity(evAppId, evActivityId)) {
                        updateTopActivity(evAppId, evActivityId)
                    }
                }
            }
        }
        if (rightAppId != topActivityFlow.value.appId) {
            synchronized(topActivityFlow) {
                // 从 锁屏，下拉通知栏 返回等情况, 应用不会发送事件, 但是系统组件会发送事件
                val topCpn = shizukuContextFlow.value.topCpn()
                if (topCpn?.packageName == rightAppId) {
                    updateTopActivity(topCpn.packageName, topCpn.className)
                } else {
                    updateTopActivity(rightAppId, null)
                }
            }
        }
        val activityRule = activityRuleFlow.value
        if (evAppId != rightAppId || activityRule.skipConsumeEvent || !storeFlow.value.enableMatch) {
            return
        }
        queryEvents.addAll(consumedEvents)
        a11yContext.interruptKey++
        startQueryJob(byEvent = latestEvent)
    }

    private var lastGetAppIdTime = 0L
    private var lastAppId: String? = null
    private suspend fun getTimeoutAppId(fresh: Boolean = false): String? {
        // A new activity can arrive inside the old package cache's 100ms lifetime.
        // Resolve state changes against the actual window, rather than losing the
        // only launch event because a recent launcher result was reused.
        if (!effective || !isInteractive) return null
        if (!fresh && lastAppId != null && System.currentTimeMillis() - lastGetAppIdTime <= 100) return lastAppId
        // 某些应用通过无障碍获取 safeActiveWindow 耗时长，导致多个事件连续堆积堵塞，无法检测到 appId 切换导致状态异常
        // https://github.com/gkd-kit/gkd/issues/622
        val activeWindow = if (fresh) {
            var root: AccessibilityNodeInfo? = null
            for (attempt in 0 until 3) {
                root = getTimeoutActiveWindow(500L, waitForSlot = true)
                if (root != null) break
                if (!effective || !isInteractive) return null
                if (!windowReadGate.isBusy()) break
                if (attempt < 2) delay(200L)
            }
            root
        } else {
            getTimeoutActiveWindow(100L)
        }
        lastAppId = activeWindow?.packageName?.toString() ?: shizukuContextFlow.value.topCpn()?.packageName
        lastGetAppIdTime = System.currentTimeMillis()
        return lastAppId
    }

    private suspend fun getTimeoutActiveWindow(
        timeoutMs: Long = 500L,
        waitForSlot: Boolean = false,
    ): AccessibilityNodeInfo? {
        if (!effective || !isInteractive) return null
        val root = windowReadGate.read(scope, Dispatchers.IO, timeoutMs, waitForSlot) { readActiveWindow() }
        // A timed-out read cannot publish its late result into the live node cache.
        if (!effective || !isInteractive) return null
        a11yContext.rootCache.value = root
        return root
    }

    private val queryRunner = QingpingQueryRunner()
    private val followup = QingpingFollowup()
    private val pendingDelayRules = linkedSetOf<ResolvedRule>()

    private fun hasQingpingRules(): Boolean {
        val top = topActivityFlow.value
        val settings = storeFlow.value
        return isInteractive && settings.enableMatch && activityRuleFlow.value.currentRules.any { rule ->
            QingpingPolicy.allows(QingpingPolicy.kind(rule.g.group.name),
                top.appId in settings.qingpingExitApps, true,
                System.currentTimeMillis() - appChangeTime, rule is AppRule)
        }
    }

    @Synchronized
    private fun startQueryJob(
        byEvent: A11yEvent? = null,
        byForced: Boolean = false,
        byDelayRule: ResolvedRule? = null,
    ) {
        if (!effective) return
        if (!storeFlow.value.enableMatch) { stopMatching(); return }
        if (!hasQingpingRules()) { followup.cancel(); pendingDelayRules.clear(); queryEvents.clear(); return }
        val activeRules = activityRuleFlow.value.currentRules
        pendingDelayRules.removeAll { delayed -> activeRules.none { it === delayed } }
        if (byDelayRule != null && activeRules.any { it === byDelayRule }) pendingDelayRules.add(byDelayRule)
        val pendingDelay = pendingDelayRules.firstOrNull()
        scheduleMatchDelays()
        // 无障碍从零启动时获取 safeActiveWindow 非常耗时
        if (byEvent == null && service.justStarted && !hasOthersService) return checkFutureStartJob()
        val started = queryRunner.tryStart(scope, queryDispatcher, onComplete = {
            val hasDelay = synchronized(this) { pendingDelayRules.isNotEmpty() }
            if ((hasDelay || queryEvents.hasPending()) && hasQingpingRules()) {
                followup.replace(scope, 0) { startQueryJob() }
            } else checkFutureStartJob()
        }) {
            followup.cancel()
            val st = if (META.debuggable) System.currentTimeMillis() else 0L
            try {
                if (META.debuggable) {
                    Log.d(
                        "A11yRuleEngine",
                        "startQueryJob start byEvent=${byEvent != null}, byForced=$byForced, byDelayRule=${byDelayRule != null}"
                    )
                }
                queryAction(byEvent, byForced, pendingDelay)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { LogUtils.d(e) }
            finally {
                if (META.debuggable) {
                    val et = System.currentTimeMillis() - st
                    Log.d("A11yRuleEngine", "startQueryJob end $et ms")
                }
            }
        }
        if (started && pendingDelay != null) pendingDelayRules.remove(pendingDelay)
    }

    private fun scheduleMatchDelays() {
        if (!storeFlow.value.enableMatch) return
        val activityRule = activityRuleFlow.value
        val exitEnabled = topActivityFlow.value.appId in storeFlow.value.qingpingExitApps
        activityRule.currentRules.forEach { rule ->
            if (!QingpingPolicy.allows(QingpingPolicy.kind(rule.g.group.name), exitEnabled,
                    isInteractive, System.currentTimeMillis() - appChangeTime, rule is AppRule)) return@forEach
            if (rule.status == RuleStatus.Status3 && rule.matchDelayJob.value == null) {
                rule.matchDelayJob.value = scope.launch(actionDispatcher) {
                    delay(rule.remainingMatchDelay())
                    rule.matchDelayJob.value = null
                    startQueryJob(byDelayRule = rule)
                }
            }
        }
    }

    @Synchronized
    private fun checkFutureStartJob() {
        if (!effective || !storeFlow.value.enableMatch || !hasQingpingRules()) { followup.cancel(); return }
        val now = System.currentTimeMillis()
        val top = topActivityFlow.value
        val settings = storeFlow.value
        val age = now - appChangeTime
        val rules = activityRuleFlow.value.currentRules.map { rule ->
            val allowed = QingpingPolicy.allows(QingpingPolicy.kind(rule.g.group.name),
                top.appId in settings.qingpingExitApps, isInteractive, age, rule is AppRule)
            QingpingPollingRule(allowed,
                rule.status == RuleStatus.StatusOk || rule.status == RuleStatus.Status5,
                rule.forcedRemaining(now), rule.actionCooldownRemaining(now))
        }
        val delayMs = qingpingFollowupDelay(rules, age, now - lastTriggerTime)
        if (delayMs == null) { followup.cancel(); return }
        val boundedDelay = if (top.appId !in settings.qingpingExitApps)
            minOf(delayMs, (QingpingPolicy.STARTUP_WINDOW_MS - age + 1).coerceAtLeast(1)) else delayMs
        followup.replace(scope, boundedDelay) {
            val current = activityRuleFlow.value.currentRules
            val currentTop = topActivityFlow.value
            val currentSettings = storeFlow.value
            val currentAge = System.currentTimeMillis() - appChangeTime
            val hasForcedRule = current.any { rule ->
                val allowed = QingpingPolicy.allows(
                    QingpingPolicy.kind(rule.g.group.name),
                    currentTop.appId in currentSettings.qingpingExitApps,
                    isInteractive,
                    currentAge,
                    rule is AppRule,
                )
                allowed && (rule.status == RuleStatus.StatusOk || rule.status == RuleStatus.Status5) &&
                    rule.checkForced()
            }
            startQueryJob(byForced = hasForcedRule)
        }
    }

    @Synchronized
    fun stopMatching() {
        followup.cancel()
        pendingDelayRules.clear()
        queryEvents.clear()
        queryRunner.cancel()
        activityRuleFlow.value.currentRules.forEach { rule ->
            rule.matchDelayJob.getAndUpdate { null }?.cancel()
            rule.cancelActionDelay()
        }
    }

    fun onScreenInactive() {
        stopMatching()
        eventDeque.clear()
        eventRunner.cancel()
        latestStateEvent = null
        a11yContext.interruptKey++
    }

    private fun fixAppId(rightAppId: String) {
        if (topActivityFlow.value.appId == rightAppId) return
        synchronized(topActivityFlow) {
            val topCpn = shizukuContextFlow.value.topCpn()
            if (topCpn?.packageName == rightAppId) {
                updateTopActivity(topCpn.packageName, topCpn.className)
            } else {
                updateTopActivity(rightAppId, null)
            }
        }
        followup.replace(scope, 300) { startQueryJob() }
    }

    private suspend fun queryAction(
        byEvent: A11yEvent? = null,
        byForced: Boolean = false,
        delayRule: ResolvedRule? = null,
    ) {
        a11yContext.cancellationJob = currentCoroutineContext()[Job]
        val appId = topActivityFlow.value.appId
        val age = System.currentTimeMillis() - appChangeTime
        val exitEnabled = storeFlow.value.qingpingExitApps.contains(appId)
        if (!isInteractive || (age > QingpingPolicy.STARTUP_WINDOW_MS && !exitEnabled)) return
        val tempStateEvent = latestStateEvent
        val newEvents = if (delayRule != null) {// 延迟规则不消耗事件
            null
        } else {
            if (byEvent != null && !queryEvents.hasPending()) return
            queryEvents.take()
        }
        val activityRule = synchronized(topActivityFlow) { activityRuleFlow.value }
        if (activityRule.skipMatch) {
            // 如果当前应用没有规则/暂停匹配, 则不去调用获取事件节点避免阻塞
            return
        }
        var lastNode = if (newEvents == null || newEvents.size <= 1) {
            newEvents?.firstOrNull()?.safeSource
        } else {
            // 获取最后两个事件, 如果最后两个事件的节点不一致, 则丢弃
            // 相等则是同一个节点发出的连续事件, 常见于倒计时界面
            val lastNode = newEvents.last().safeSource
            if (lastNode == null || lastNode == newEvents[0].safeSource) {
                lastNode
            } else {
                null
            }
        }
        var lastNodeUsed = false
        if (!a11yContext.clearOldAppNodeCache()) {
            if (byEvent != null) { // 此为多数情况
                // 新事件到来时, 若缓存清理不及时会导致无法查询到节点
                a11yContext.clearNodeCache(lastNode)
            }
        }
        for (rule in activityRule.priorityRules) { // 规则数量有可能过多导致耗时过长
            if (!QingpingPolicy.allows(
                    QingpingPolicy.kind(rule.g.group.name), exitEnabled, isInteractive,
                    System.currentTimeMillis() - appChangeTime, rule is AppRule
                )) continue
            if (!effective) return
            if (checkOutDate(activityRule, tempStateEvent)) break
            if (delayRule != null && delayRule !== rule) continue
            if (rule.status != RuleStatus.StatusOk) continue
            if (byForced && !rule.checkForced()) continue
            lastNode?.let { n ->
                val refreshOk = (!lastNodeUsed) || (try {
                    val e = n.refresh()
                    if (e) {
                        n.setGeneratedTime()
                    }
                    e
                } catch (_: Throwable) {
                    false
                })
                lastNodeUsed = true
                if (!refreshOk) {
                    lastNode = null
                }
            }
            val nodeVal = (lastNode ?: getTimeoutActiveWindow()) ?: return
            val rightAppId = nodeVal.packageName?.toString() ?: break
            val matchApp = rule.matchActivity(rightAppId)
            if (topActivityFlow.value.appId != rightAppId || (!matchApp && rule is AppRule)) {
                scope.launch(eventDispatcher) { fixAppId(rightAppId) }
                return
            }
            if (!matchApp) continue
            val target = a11yContext.queryRule(rule, nodeVal) ?: continue
            if (rule.checkDelay() && rule.actionDelayJob.value == null) {
                rule.actionDelayJob.value = scope.launch(actionDispatcher) {
                    delay(rule.actionDelay)
                    rule.actionDelayJob.value = null
                    startQueryJob(byDelayRule = rule)
                }
                continue
            }
            if (rule.status != RuleStatus.StatusOk) break
            if (checkOutDate(activityRule, tempStateEvent)) break
            val topActivity = activityRule.topActivity
            if (!isInteractive || !storeFlow.value.enableMatch) return
            if (target.packageName?.toString() != topActivity.appId || !target.isVisibleToUser) continue
            // Recheck the actual window immediately before acting on a previously matched node.
            val freshRoot = getTimeoutActiveWindow() ?: return
            if (freshRoot.packageName?.toString() != topActivity.appId) return
            if (checkOutDate(activityRule, tempStateEvent)) return
            if (!target.refresh() || target.packageName?.toString() != topActivity.appId || !target.isVisibleToUser) continue
            val freshContext = A11yContext(this, interruptable = false).apply {
                rootCache.value = freshRoot
                cancellationJob = currentCoroutineContext()[Job]
            }
            if (freshContext.queryRule(rule, freshRoot) != target || freshContext.incompleteRead) continue
            if (checkOutDate(activityRule, tempStateEvent)) return
            if (!effective || !QingpingPolicy.canAct(QingpingPolicy.kind(rule.g.group.name),
                    topActivity.appId in storeFlow.value.qingpingExitApps, isInteractive,
                    System.currentTimeMillis() - appChangeTime, rule is AppRule,
                    storeFlow.value.enableMatch, freshRoot.windowId == target.windowId)) return
            val actionResult = rule.performAction(target)
            if (actionResult.result) {
                rule.trigger()
                if (actionResult.action != ActionPerformer.None.action) {
                    showActionToast(rule)
                }
                addActionLog(rule, topActivity, target, actionResult)
                if (actionResult.action != ActionPerformer.None.action) {
                    val outcome = verifyQingpingOutcome(rule, topActivity.appId)
                    DbSet.qingpingStatDao.record(topActivity.appId, "verify_${outcome.name.lowercase()}", System.currentTimeMillis())
                    return // Finish verification before considering another real action.
                }
                // A none action can arm preKeys; continue the chain without requiring a new event.
            }
        }
    }

    private suspend fun verifyQingpingOutcome(rule: ResolvedRule, appId: String): QingpingOutcome {
        repeat(2) { sample ->
            delay(if (sample == 0) 300L else 150L)
            if (!isInteractive || !storeFlow.value.enableMatch || !effective) return QingpingOutcome.UNKNOWN
            val root = getTimeoutActiveWindow() ?: return QingpingOutcome.UNKNOWN
            val packageName = root.packageName?.toString() ?: return QingpingOutcome.UNKNOWN
            if (packageName != appId) return QingpingOutcome.EXTERNAL
            val available = synchronized(topActivityFlow) {
                activityRuleFlow.value.priorityRules.any { it === rule }
            }
            if (!available) return QingpingOutcome.UNKNOWN
            val readable = runCatching {
                root.refresh() && qingpingReadableTree(root, { it.childCount }, { n, i -> n.getChild(i) },
                    { it.isVisibleToUser && (!it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()) })
            }.getOrDefault(false)
            if (!readable) return QingpingOutcome.UNKNOWN
            val context = A11yContext(this, interruptable = false).apply {
                rootCache.value = root
                cancellationJob = currentCoroutineContext()[Job]
            }
            val present = try { context.queryRule(rule, root) != null }
                catch (e: CancellationException) { throw e }
                catch (_: Throwable) { return QingpingOutcome.UNKNOWN }
            if (context.incompleteRead) return QingpingOutcome.UNKNOWN
            val outcome = QingpingOutcomePolicy.decide(true, available, readable, present)
            if (outcome != QingpingOutcome.EXIT_GONE) return outcome
        }
        return QingpingOutcome.EXIT_GONE
    }

    private fun checkOutDate(
        activityRule: ActivityRule,
        stateEvent: A11yEvent?
    ): Boolean {
        if (stateEvent !== latestStateEvent) return true
        synchronized(topActivityFlow) {
            if (activityRule !== activityRuleFlow.value) return true
        }
        return false
    }

    companion object {
        val service: A11yCommonImpl?
            get() = uiAutomationFlow.value?.takeIf {
                it.mode.value == latestServiceMode.value
            } ?: A11yService.instance
        val instance: A11yRuleEngine? get() = service?.ruleEngine

        fun compatWindows(): List<AccessibilityWindowInfo> {
            return try {
                service?.windowInfos
            } catch (_: Throwable) {
                null
            } ?: emptyList()
        }

        fun onScreenForcedActive() {
            instance?.onScreenForcedActive()
        }

        fun performActionBack(): Boolean {
            val r1 = shizukuContextFlow.value.inputManager?.key(KeyEvent.KEYCODE_BACK)
            if (r1 != null) return true
            return A11yService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
        }

        suspend fun screenshot(): Bitmap? = service?.screenshot()

        suspend fun execAction(gkdAction: GkdAction): ActionResult {
            val selector = Selector.parseOrNull(gkdAction.selector) ?: throw RpcError("非法选择器")
            runCatching { selector.checkType(typeInfo) }.exceptionOrNull()?.let {
                throw RpcError("选择器类型错误:${it.message}")
            }
            val s = instance ?: throw RpcError("服务未连接")
            val a = s.safeActiveWindow ?: throw RpcError("界面没有节点信息")
            val targetNode = A11yContext(s, interruptable = false).querySelfOrSelector(
                a, selector, MatchOption(fastQuery = gkdAction.fastQuery)
            ) ?: throw RpcError("没有查询到节点")
            return withContext(Dispatchers.IO) {
                ActionPerformer
                    .getAction(gkdAction.action ?: ActionPerformer.None.action)
                    .perform(targetNode, gkdAction)
            }
        }

    }
}
