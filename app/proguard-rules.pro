# 异常类名是**给用户看的兜底文案**：`e.message ?: e.javaClass.simpleName`（ConnectionFailure、
# HomeViewModel.shortMessage 等七八处）。混淆后 `IOException` 会变成 `a`，
# 连不上时用户看到的就是一个单字母。keepnames 只保名字，用不到的类照样能被裁掉。
-keepnames class * extends java.lang.Throwable

# sshj 大量走反射装载算法实现与 SPI，混淆后会在运行期找不到类。
-keep class com.hierynomus.** { *; }
-keep class net.schmizz.** { *; }

# BouncyCastle 整包 keep 会把 4MB dex 全钉死，R8 一个字节都裁不掉。下面这串否定前缀
# 把「SSH 永远够不着的门面包」踢出 keep 集合——注意只是不再**当根**，
# 真被引用到的类照样按可达性保留，所以裁掉的都是没人引用的死代码（实测 -688KB）。
#   pqc  后量子签名/KEM：sshj 0.40 的 KEX 工厂表（DefaultConfig.initKeyExchangeFactories）
#        从头到尾只有 DH / ECDH-nistp / curve25519，没有 sntrup761x25519 也没有 mlkem768x25519。
#        BouncyCastleProvider 反射拼 `$Mappings` 类名只覆盖 jcajce.provider 下的
#        asymmetric/digest/drbg/kdf/keystore/symmetric 六个前缀，够不到 pqc；
#        而 loadPQCKeys() 里那二十来个 *KeyFactorySpi 是**静态**引用，R8 按可达性照样保留
#        （只是改了名），所以 new BouncyCastleProvider() 不会 NoClassDefFoundError。
#   其余 cms/tsp/est/its/oer/mime/dvcs/eac/voms/mozilla/cmc/x509 全是 bcpkix 给
#        S/MIME、时间戳、车联网证书等场景准备的 API 门面，SSH 与密钥文件解析都不碰。
#
# 下面六个包是 BC 1.85 才新增的，1.78.1 里压根不存在，所以写这串列表时漏了它们，
# 升级后整包被当根钉死（合计 ~82KB dex）。逐个核实过「没有任何一处按名字反射装载它们」：
# BouncyCastleProvider 只从 jcajce.provider 下的 asymmetric/digest/drbg/kdf/keystore/symmetric
# 六个前缀拼 `$Mappings` 类名，这六个包一个都不在里面。
#   cert.plants  IETF PLANTS 草案的 Merkle Tree 证书（landmark CA、cosigner 那一套）。
#        全 BC 三个 jar 里没有任何一处引用它，只能由调用方直接点名；而 SSH 的主机密钥
#        是裸公钥或 OpenSSH 证书，由 sshj 自己解析，从不进 X.509 世界。
#   cert.ct  证书透明度的 SCT 结构，是 TLS 证书链校验的配套，同样零引用。
#   cades  CAdES 高级电子签名，建在已经被踢掉的 cms 之上，本来就够不着。
#   crypto.threshold  Shamir 门限拆分的 API，SSH 私钥是整份读进来用的，不存在分片。
#   crypto.bls / crypto.hash2curve  BLS12-381 配对签名与 RFC 9380 hash-to-curve，
#        只被 BLSSigner / BLSKeyPairGenerator 这几个同样没人调的门面牵着；
#        SSH 的签名算法只有 rsa/ecdsa/ed25519，握手也不做配对运算。
-keep class !org.bouncycastle.pqc.**,
            !org.bouncycastle.cert.plants.**,
            !org.bouncycastle.cert.ct.**,
            !org.bouncycastle.cades.**,
            !org.bouncycastle.crypto.threshold.**,
            !org.bouncycastle.crypto.bls.**,
            !org.bouncycastle.crypto.hash2curve.**,
            !org.bouncycastle.oer.**,
            !org.bouncycastle.cms.**,
            !org.bouncycastle.its.**,
            !org.bouncycastle.tsp.**,
            !org.bouncycastle.est.**,
            !org.bouncycastle.mime.**,
            !org.bouncycastle.dvcs.**,
            !org.bouncycastle.eac.**,
            !org.bouncycastle.voms.**,
            !org.bouncycastle.mozilla.**,
            !org.bouncycastle.cmc.**,
            !org.bouncycastle.x509.**,
            org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.schmizz.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.naming.**
