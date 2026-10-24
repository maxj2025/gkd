# GKD Fork 代码审查

审查日期：2026-10-24。结论针对下面固定提交，按“省电实现 → 开机恢复 → 防误触 → 结果验证”的顺序检查，并与清屏 1.0.0 对照。

| 项目 | 检查的提交 | 重点 |
| --- | --- | --- |
| 清屏 | `3c2805d39225c774d15113e3158d8648b82f86bb` | 现有实现及移植收益 |
| gkd_pure | `4ed09bba461d89b1a705abd4b5b8667b358e5e9a` | 查询去重、轮询退避、事件队列、日志与权限刷新 |
| GKD-Plus | `f1dd50a1a9c20f6ae8acd2089b2945434b61c214` | 开机接收器、安全设置权限、磁贴恢复 |
| GKD 特调版 | `f3ce92ec6ac8324014d733eca26c33018cd5e697` | 假跳过、跨应用跳转、坐标保护及守护 |
| Bypass Ads | `20528e0b60cc1eee19be4c7c6f1b872caf0a92f6` | 动作后验证、证据归属、重试预算与测试覆盖 |

这是关键调用链的静态审查，并非整仓安全审计。本轮未编译或运行四个分支，未重新运行其测试，也未进行小米13耗电或后台清理实测。项目自述的测试数量、真机结论不作为本轮独立验证结果。

## 1. gkd_pure：有可借鉴的实现，不能整段复制

### 1.1 查询任务去重：优先移植

清屏在同步方法中检查 `querying`，但在 `launchTry(queryDispatcher)` 的协程体开始后才置为 `true`。如果两次调用都发生在第一个协程开始执行之前，两次都能通过检查，形成重复排队。查询分发器是单线程，但协程可能挂起；单线程不能替代提交任务时的占位。

gkd_pure 将置位移到同步方法内、提交协程之前，消除了这个提交窗口。它同时维护一个 `futureStartJob`，在重新安排后续查询时取消旧任务，避免持续积累延迟查询。

清屏移植时还应处理“任务在执行前取消”的状态释放，不能仅依靠协程体内的 `finally`；否则提前置位后可能没有机会复位。建议由任务完成回调或明确的任务所有权管理占位，且避免旧任务释放新任务的状态。

依据：[gkd_pure 查询调度](https://github.com/cookie-kangd/gkd_pure/blob/4ed09bba461d89b1a705abd4b5b8667b358e5e9a/gkd-app/src/main/kotlin/li/gkd/app/a11y/A11yRuleEngine.kt#L253)、[清屏现有实现](https://github.com/maxj2025/gkd/blob/3c2805d39225c774d15113e3158d8648b82f86bb/app/src/main/kotlin/li/songe/gkd/a11y/A11yRuleEngine.kt#L250)。

### 1.2 强制轮询退避：选择性移植

它按照有效事件的新近程度、前台应用活跃时长和规则冷却剩余时间决定下一次查询：近期事件约300ms，之后500ms或1000ms；等待冷却时可延长，最大受5000ms上限约束。新事件仍可立即触发查询。

这是实质性的减少空转实现，但不能直接覆盖清屏的15秒截止策略。清屏应先检查屏幕、功能开关、可执行广告规则和截止时间，确实需要继续等待时才安排一次退避查询。对开启跳转防护的应用，也应限定实际需要的规则与页面。

依据：[退避计算](https://github.com/cookie-kangd/gkd_pure/blob/4ed09bba461d89b1a705abd4b5b8667b358e5e9a/gkd-app/src/main/kotlin/li/gkd/app/a11y/A11yRuleEngine.kt#L334)。

### 1.3 权限与事件日志：有收益，但要准确限定场景

- 工作模式页改为在 `ON_RESUME` 时刷新权限，替代每秒轮询。清屏仍有授权页 ViewModel 的每秒检查；这是页面存活期间的开销，不能说它是全应用长期耗电的主因。事件刷新方式还需覆盖应用停留前台时由ADB等途径改变权限的情况。
- 事件日志服务在没有待写数据时使用 `first { it.isNotEmpty() }` 挂起，有数据后再延时批量写入，并在退出时尝试刷尾。清屏启用事件日志服务时仍每秒检查一次；该服务没启用时，这项没有对应收益。
- `SimpleDateFormat` 缓存改成每线程一份，避免共享非线程安全实例。清屏仍共享一个可变缓存；这首先是正确性改进，不能量化为续航提升。
- 事件队列与查询事件分别设置64、32条上限，有助于限制事件风暴下的积压。但丢弃事件会改变行为，移植前需要覆盖快速切应用、延迟动作与末次事件保留，不能机械采用这两个数字。

依据：[权限刷新](https://github.com/cookie-kangd/gkd_pure/blob/4ed09bba461d89b1a705abd4b5b8667b358e5e9a/gkd-app/src/main/kotlin/li/gkd/app/feature/settings/WorkModeVm.kt)、[日志批量写入](https://github.com/cookie-kangd/gkd_pure/blob/4ed09bba461d89b1a705abd4b5b8667b358e5e9a/gkd-app/src/main/kotlin/li/gkd/app/service/EventService.kt#L163)、[格式化缓存](https://github.com/cookie-kangd/gkd_pure/blob/4ed09bba461d89b1a705abd4b5b8667b358e5e9a/gkd-app/src/main/kotlin/li/gkd/app/util/TimeExt.kt#L39)、[事件队列边界](https://github.com/cookie-kangd/gkd_pure/blob/4ed09bba461d89b1a705abd4b5b8667b358e5e9a/gkd-app/src/main/kotlin/li/gkd/app/a11y/A11yRuleEngine.kt#L43)。

它已经采用新版上游模块与包结构，清屏仍采用1.12.1基线。其保护应用名单、界面和整套架构不宜随性能修复一起搬入。

## 2. GKD-Plus：开机恢复确有实现，磁贴恢复已有

### 2.1 可以借鉴的新增部分

`BootRestoreReceiver` 注册开机广播；只在自动化总开关开启、当前为无障碍模式时尝试恢复，最多三轮。特权连接初始化会执行 `ensureWriteSecureSettings()`，在非Root情况下通过特权进程运行 `pm grant`，获得后续直接写设置所需的权限。

因此它确实有“一次授权后，开机恢复不依赖Shizuku常驻”的代码路径。不过，权限仍有效、系统允许开机广播送达、自启动未被限制，是恢复链路成立的前提。它不能保证被强行停止后自我恢复，也不保证设备尚未解锁时生效。

依据：[开机恢复](https://github.com/fjjzy/gkd-plus/blob/f1dd50a1a9c20f6ae8acd2089b2945434b61c214/gkd-app/src/main/kotlin/li/gkd/app/service/BootRestoreReceiver.kt#L24)、[权限补授](https://github.com/fjjzy/gkd-plus/blob/f1dd50a1a9c20f6ae8acd2089b2945434b61c214/gkd-app/src/main/kotlin/li/gkd/app/priv/PrivilegeContext.kt#L185)。

### 2.2 不应重复移植的部分

清屏的 `BaseTileService` 已在磁贴开始监听时触发 `fixRestartAutomatorService()`，并有3秒节流；`PackageManager.grantSelf()` 也已包含安全设置权限授权。

不能把“下拉磁贴自检”和“可以取得安全设置权限”整体计作新功能。新增价值主要是开机触发入口，以及不同特权后端下授权失败的补救。

依据：[清屏已有磁贴自检](https://github.com/maxj2025/gkd/blob/3c2805d39225c774d15113e3158d8648b82f86bb/app/src/main/kotlin/li/songe/gkd/service/BaseTileService.kt#L26)。

### 2.3 必须修正后再移植：旧集合回写竞态

恢复器读取无障碍服务集合 `names`，移除自身，等待600ms，再把自身加入同一份旧集合并整体写回。如果这段时间用户或其他应用改变了另一项无障碍服务，后一次写回会覆盖该变化。恢复器也没有复用磁贴路径的互斥入口；自动化开关只在恢复开始时读取，等待中关闭开关不会阻止本轮继续写入。

建议统一所有自身恢复入口；每次写入前重新读取当前集合，只增删自身组件，并重新检查用户意愿。应用内互斥只能协调自身写入，不能消除其他进程修改系统设置的全部竞态。优先等待系统绑定，确认持续故障后才尝试摘除重启。

依据：[读取、等待及旧集合回写](https://github.com/fjjzy/gkd-plus/blob/f1dd50a1a9c20f6ae8acd2089b2945434b61c214/gkd-app/src/main/kotlin/li/gkd/app/service/BootRestoreReceiver.kt#L51)。

## 3. GKD 特调版：借鉴坐标防护，谨慎采用自动返回和保活

### 3.1 坐标防护值得优先移植

在坐标动作前检查空或反向矩形；对不可见节点范围内的坐标点击进行拦截，并节流记录拒绝原因。这些检查可以避免一些明显无效的手势。

它有意允许规则把坐标偏移到节点外，因此并非“所有点击必须落在节点内”。清屏移植应保留这一区别，并补充数值有效性、当前窗口归属和动作前页面一致性检查。

依据：[坐标检查](https://github.com/bingguang1/GKD-tetiao/blob/f3ce92ec6ac8324014d733eca26c33018cd5e697/app/src/main/kotlin/li/songe/gkd/data/GkdAction.kt#L37)。

### 3.2 假跳过判据不能当作确定事实

`SkipTreeJudge` 最多遍历500个节点，通过“包含点击点的可点节点面积”判断覆盖层。它不重建真实绘制层级和触摸分发关系，大型可点祖先容器也可能满足条件，不能据此确定按钮是假跳过。

另外，未知结果默认放行；规则显式声明可点、以及可点击目标，会绕过部分假跳过判定。事后校验等待约1.2秒，先读取前台缓存；同包名即返回，无法证明同一App内的广告落地页已经关闭。若事前判为 `Real`，跨应用后也不自动返回。这些都是明确的取舍，不是完整的广告跳转识别能力。

依据：[树判定](https://github.com/bingguang1/GKD-tetiao/blob/f3ce92ec6ac8324014d733eca26c33018cd5e697/app/src/main/kotlin/li/songe/gkd/service/SkipTreeJudge.kt#L58)、[事后校验](https://github.com/bingguang1/GKD-tetiao/blob/f3ce92ec6ac8324014d733eca26c33018cd5e697/app/src/main/kotlin/li/songe/gkd/service/FakeSkipGuard.kt#L256)。

### 3.3 跨应用自动返回可能误伤正常操作

`JumpGuard.evaluate()` 的关键条件是：来源应用在名单、跳转发生在时间窗口内、目标不属于若干排除项。`shakeSeen` 只用于日志和提示，并不是执行返回的必要条件。

因此，名单内App刚打开时，用户主动跳转浏览器登录、支付或外部链接，也可能被退回。系统面板、输入法和选择器排除只能减少部分误伤，不能识别全部正常跳转。

建议先借鉴“记录可疑跳转”和“动作后的结果核对”，不默认启用窗口内跨应用一律返回。自动返回应增加明确广告证据、来源页面适配和用户授权，并与正常跳转测试一起验证。

依据：[跳转条件与动作](https://github.com/bingguang1/GKD-tetiao/blob/f3ce92ec6ac8324014d733eca26c33018cd5e697/app/src/main/kotlin/li/songe/gkd/service/JumpGuard.kt#L230)。

### 3.4 定时守护存在可靠性缺口，也增加唤醒

实现使用 `setExactAndAllowWhileIdle(RTC_WAKEUP, …)`，未运行时约2分钟、正常时约10分钟安排检查。本次在仓库XML及Kotlin配置中没有检索到精确闹钟权限声明、`canScheduleExactAlarms()` 检查或失败后的非精确闹钟回退；`scheduleAlarm()` 把异常包在无错误处理的 `runCatching` 中。

在不具备相应权限或系统豁免的设备上，安排闹钟可能失败并静默中断兜底链。不能把这一实现视为所有设备都能生效。关联应用守护还有亮屏约5秒、熄屏约10秒的循环检查，未运行时约4.5秒尝试抢救；它并非完全没有周期唤醒。

建议优先采用开机、解锁、回到前台等事件驱动的有限恢复。定时守护只作为明确开启的可选项，显示不可用原因；不承诺精确间隔，不用于宣称强停后仍然有效。

依据：[闹钟实现](https://github.com/bingguang1/GKD-tetiao/blob/f3ce92ec6ac8324014d733eca26c33018cd5e697/app/src/main/kotlin/li/songe/gkd/service/A11yAutoGuard.kt#L67)、[循环检查](https://github.com/bingguang1/GKD-tetiao/blob/f3ce92ec6ac8324014d733eca26c33018cd5e697/app/src/main/kotlin/li/songe/gkd/service/AssocAppGuard.kt#L42)、[Android精确闹钟要求](https://developer.android.com/develop/background-work/services/alarms/schedule)。

## 4. Bypass Ads：验证框架值得借鉴，成功判定还需加固

### 4.1 已接入真实执行链，具有实质价值

引擎在执行前固定本次动作的证据，包括规则身份和目标区域；执行后等待300ms，重新取根节点并检查同一广告。初次认为成功时再等150ms复查。没有可用根节点时返回未知；跳到已知浏览器、应用商店或桌面时标为疑似误触。

验证并非仅供展示：引擎依据结果决定停止、重试或结束会话。重试预算在动作前通过原子比较交换预留，失败动作也占用次数；验证任务完成或取消后释放执行令牌，避免无限点击和重复调度。

依据：[验证器](https://github.com/cuppur/bypass-ads/blob/20528e0b60cc1eee19be4c7c6f1b872caf0a92f6/app/src/main/kotlin/li/songe/gkd/bypass/BypassOutcomeVerifier.kt#L95)、[引擎接入](https://github.com/cuppur/bypass-ads/blob/20528e0b60cc1eee19be4c7c6f1b872caf0a92f6/app/src/main/kotlin/li/songe/gkd/a11y/A11yRuleEngine.kt#L824)、[动作预算](https://github.com/cuppur/bypass-ads/blob/20528e0b60cc1eee19be4c7c6f1b872caf0a92f6/app/src/main/kotlin/li/songe/gkd/bypass/BypassActionBudget.kt#L20)。

### 4.2 成功仍然是有限证据下的推断

根节点非空、仍在同一包、两次没找到同一广告时，会进入 `SUCCESS_CONFIRMED`。但非空根节点不等于页面内容已经完整可读：加载页连续两次只有空容器，也可能满足“没找到广告”。

此外，`hasSameAdCandidate()` 在证据身份不全或当前规则集合里找不到原规则时返回 `false`，没有区分“规则确实匹配不到”与“已无法执行原验证”。这种证据丢失不应当支撑成功。广告区域使用固定500物理像素阈值，对不同分辨率和布局并不等价。

清屏可借鉴证据快照、有限复查和预算，但应将匹配结果设计成“存在／不存在／不可验证”，缺少原规则或页面不可读时保留未知；区域比较使用屏幕归一化或目标尺寸比例。没有正常页面信号时，不仅凭按钮消失就累计为确定成功。

依据：[两次采样及判定](https://github.com/cuppur/bypass-ads/blob/20528e0b60cc1eee19be4c7c6f1b872caf0a92f6/app/src/main/kotlin/li/songe/gkd/bypass/BypassOutcomeVerifier.kt#L117)、[原规则复查](https://github.com/cuppur/bypass-ads/blob/20528e0b60cc1eee19be4c7c6f1b872caf0a92f6/app/src/main/kotlin/li/songe/gkd/a11y/A11yRuleEngine.kt#L1073)。

### 4.3 测试存在，但不等于本轮验证通过

仓库确有 `OutcomeVerifierTest`、`ActionBudgetTest` 等行为测试，覆盖外部跳转、根不可用、证据缺失、多阶段出口和次数预算。部分验证器测试使用纯判定函数或注入的提供者，不能替代真实无障碍树、加载状态及不同厂商后台行为测试。

OCR、持续观察和黑匣子不是移植验证器的必要依赖。对清屏应先只做动作后的短期复查，不引入整套持续扫描；详细诊断按需开启，不保存不必要的界面文本或截图。

## 5. 对照发现：清屏已有动作结果错误，应先修

清屏的坐标点击、长按和滑动路径使用类似表达式：

```kotlin
A11yService.instance?.dispatchGesture(...) != null
```

当服务存在，但 `dispatchGesture` 返回 `false` 时，可空布尔值仍不为 `null`，表达式会得到 `true`，造成失败动作被当成成功。特调版也保留了相同模式，因此它的坐标保护没有修复这个结果语义错误。

最小修正是明确比较 `== true`，区分系统拒绝与接受；若要称为手势执行完成，还需处理完成／取消回调。即使完成，也不能据此断言广告已消失，仍需独立的界面结果验证。

依据：[清屏坐标点击](https://github.com/maxj2025/gkd/blob/3c2805d39225c774d15113e3158d8648b82f86bb/app/src/main/kotlin/li/songe/gkd/data/GkdAction.kt#L82)、[特调版同类表达式](https://github.com/bingguang1/GKD-tetiao/blob/f3ce92ec6ac8324014d733eca26c33018cd5e697/app/src/main/kotlin/li/songe/gkd/data/GkdAction.kt#L148)。

## 6. 清屏后续改动的优先级

| 顺序 | 改动 | 必须验证的行为 |
| --- | --- | --- |
| 1 | 修正手势返回值，明确统计口径 | 拒绝、取消、完成分别处理；失败不计成功 |
| 2 | 查询占位、唯一后续任务、取消清理 | 两次快速事件只排一轮；取消不永久锁住；新事件不吃掉唯一重试 |
| 3 | 格式化线程安全、权限刷新、日志空闲等待 | 多线程格式化稳定；权限变化及时显示；退出刷尾不丢记录 |
| 4 | 坐标与当前页面校验 | 空矩形、不可见目标、窗口切换拒绝；合法偏移规则仍可用 |
| 5 | 有限的结果验证及重试预算 | 迟到跳转、空白加载页、原规则丢失、多阶段广告、预算耗尽 |
| 6 | 一次授权后的事件驱动恢复 | 重启／解锁、权限不足、用户主动关闭、其他无障碍同时变化 |

保留清屏的15秒窗口、熄屏停止匹配、按App授权防护和本地累计统计。暂不默认加入精确闹钟守护、广泛跨应用自动返回、持续OCR或AI规则生成。性能收益必须通过相同应用、规则和操作条件下的实测比较确认。
