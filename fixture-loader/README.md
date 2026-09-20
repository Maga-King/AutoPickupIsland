# OS4 系统装载隔离实验

## 当前状态（2026-09-06，优先于下文历史记录）

独立 loader v2 已支持测试包与微信两个独立门控。当前 CLI 已启用，作用域仍仅 `system/0`；微信门控 `/data/system/mio-wechat-loader.enable` 存在，测试门控不存在。启动完成之前不重定向。微信不是 LSP 作用域，但其进程会通过系统工厂加载载体代码，不能把“没有业务 Hook”宣传成“进程内完全没有新增代码”或零封号风险。

载体 v4 / `0.4-page-signal` 使用 `adb install -r --force-queryable` 安装；v3 已实测真实瑞幸页面的原版 DOM + PCR。v4 新增仅由授权正文请求建立的 Binder 页面信号：有效小程序页面抬手后 350/1200ms 两次合并通知，不携带触点、文字、URL；暂停/销毁释放。它是 OS4 传输适配，不是完整 ColorOS 触发器，实时按钮测试仍待验收。

主模块当前为 0.10.1/code17，仍仅 SystemUI/VoiceAssist/AI-call/AICR 四个作用域。没有微信/QQ LSP 作用域，也没有新增截图无障碍依赖。

完整回退：`diagnostics/Restore-FixtureLoader.ps1 -IncludeWechat` 移走两个显式门控（保留可恢复标记）、停用独立 loader、停止测试包和微信以清除缓存。仅停用 LSP 不会卸载当前进程内的 Hook。默认不带参数只回退测试门控，有微信门控时不再停用共享 loader。不会覆写 LSP 数据库、清除聊天数据或重启手机。

## 历史：fixture-only 阶段

不是生产模块，不接入微信/QQ。仅当同时满足下列条件时，候选 Hook 才允许改变单次返回：

- system_server 的 `IPackageManagerBase.getPackageInfo(String,long,int)` 返回后；
- Binder UID 恰为 10483，查询包恰为 `com.miui.contentcatcher`，flags=0，user=0；
- 系统已完成启动，且 `/data/system/mio-fixture-loader.enable` 文件存在；
- 系统原查询确认该 UID 的测试包及独立载体具有本地已核对的签名；
- 本次原调用没有抛异常，本 Hook 未因累计三个错误熔断。

返回载体自己的 PackageInfo，供原 InterceptorFactory.createPackageContext 加载；不修改 PMS 缓存对象、APK、签名、授权或默认系统组件。三个异常后本次启动停止重定向。载体构造器另有测试包限制。

2026-09-05 重连实测发现：设备 Vector CLI 的系统作用域使用 `system/0`，`android/0` 虽能写入配置，但不会装载到 system_server。首次重启日志验证了这个差异。通过 CLI 改为仅 `system/0` 后，第二次重启日志确认 Hook 载入。

真实自动装载已在 `NativeFactoryDomActivity` 验证：该页面无主动加载或 UIAgent 注册代码，`native_happy` 经系统工厂/原 UIAgent 返回真实合成页面 DOM，`native_stock/expired/navigation/nonce/activity` 全通过。测试进程 maps 确认载体 APK 已加载。只证明测试包的系统自动装载，不等于微信适配、原生命周期或通知端到端已完成。

**当前已回退**：`Restore-FixtureLoader.ps1` 将 enable 标记移为 `.disabled`，通过 CLI 停用此独立模块并结束测试进程。冷启动后 `native_stock` 通过，maps 只有原 ContentCatcherOS4、没有载体；system_server / SystemUI 在测试前后均为 5912 / 8259。当前启动中 Hook 函数仍在，但没有 enable 标记就不改变返回；下次启动因模块停用不会载入。生产模块和微信/QQ 作用域没改。

回退：移走此 enable 文件后，新的请求立即回原 PMS 结果；已加载的测试进程须结束再启动，才能清除工厂类缓存。仅在 LSPosed 停用模块不等于当前进程 Hook 已卸载。上述真实启用/回退已演练。回退脚本使用独立 ADB 单命令，避免 PowerShell → adb → su 的多层条件语句转义错误。

### 原读取器组合复测

2026-09-05 已更新载体到 v2/0.2-original-fixture-only，在同一次启动中利用已有的受标记控制 Hook 验证了原 ColorOS JAR 读取端。没有重新启用持久模块或重启。普通 NativeFactory 页面通过；新增无 WebView 类名的 `$a` 合成容器，在 XML 当前已安装版本类名命中时通过、缺少匹配时正确不支持。回调接口方法名混淆与 Object 方法保护同时覆盖。

自动复现实验脚本 `diagnostics/Run-NativeOriginalCarrier.ps1`：基线 stock → 临时标记 → stock/错误请求不加载原 JAR → 首个有效请求原 JAR maps/DOM → XML 选择 → finally 回退 → 冷启动仅 stock maps。已全部通过，详见 `diagnostics/ORIGINAL_COLOROS_READER.md`。脚本不会安装 APK、自动重新启用模块或将目标扩成真实应用。最新已安装载体/fixture 哈希也记录在该文档。

用户拿走设备期间不执行 ADB、重启、开关更改或真机采集。重连后先确认前台测试页及开关状态，再继续；不能根据持久化 READY 标记推断当前仍停留在测试页面。
