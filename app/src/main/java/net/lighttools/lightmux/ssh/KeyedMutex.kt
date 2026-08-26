package net.lighttools.lightmux.ssh

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 key（实际都是 hostId）发一把 Mutex。
 *
 * 三处「按主机取连接」的地方——[ExecPool]、[net.lighttools.lightmux.sftp.SftpRepository]、
 * [net.lighttools.lightmux.forward.ForwardManager]——各持有一个实例。
 * **它们的连接管理没有合并**：持有策略是刻意不同的（池子用完即放、SFTP 按 lane 留着、
 * 转发要挂几个小时），合到一起只会把这些差异搅浑。但发锁这件事三份逐字相同，
 * 而且带着一个必须写对的细节，值得单点收口。
 *
 * 那个细节是 `computeIfAbsent`：换成 `getOrPut` 就是 get-then-put 两步，在 [ConcurrentHashMap]
 * 上**不是原子的**。两个协程首次同时访问同一台主机（展开主机的同时监控页开了轮询）会各拿到
 * 一把不同的 Mutex，「按主机串行」当场失效，两条连接同时拨向同一台机器。
 *
 * 不做清理：key 是主机 id，数量等于主机条数，一把空 Mutex 就几十字节；
 * 反过来「用完就删」得先确认没人正等在上面，那才是真会出错的地方。
 */
internal class KeyedMutex {

    private val locks = ConcurrentHashMap<String, Mutex>()

    operator fun get(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }
}
