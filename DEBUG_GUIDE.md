# 🔧 Debug Guide - Data Not Flowing & Crash Issues

## 📋 مراحل بررسی (Steps to Check):

### 1️⃣ **Logcat رو مانیتور کن**
```bash
adb logcat | grep -E "(XRAY|HEV|TUNNEL|VPN|ERROR|Exception)"
```

### 2️⃣ **این پیام‌ها رو جستجو کن:**

```
✅ موفق:
- "Tun interface established. FD allocated successfully"
- "Xray's SOCKS5 inbound...came up in time"
- "Handing TUN fd to hev-socks5-tunnel"

❌ خطا:
- "libxray.so not found"
- "libhev-socks5-tunnel.so failed to load"
- "Xray's SOCKS5 inbound never came up in time"
- "UnsatisfiedLinkError"
- "NullPointerException"
```

### 3️⃣ **اگر crash داره، این کد رو test کن:**

```kotlin
// V2RayVpnService.kt - اضافه کن تا بیشتر log باشه

private fun startVpn() {
    // ... existing code ...
    
    serviceScope.launch {
        try {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)
            val server = repository.getSelectedServer()

            if (server == null) {
                repository.log("VPN", "ERROR", "No active server selected!")
                return@launch
            }

            // Add try-catch برای هر مرحله
            try {
                val builder = Builder()
                    .setSession("V2RayDan")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1400)

                repository.log("TUNNEL", "DEBUG", "TUN builder created")

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    repository.log("TUNNEL", "DEBUG", "Disallowed app failed: ${e.message}")
                }

                val fd = builder.establish()
                if (fd == null) {
                    repository.log("TUNNEL", "ERROR", "TUN fd is NULL!")
                    return@launch
                }
                
                repository.log("TUNNEL", "SUCCESS", "TUN fd established: ${fd.fd}")

            } catch (e: Exception) {
                repository.log("TUNNEL", "ERROR", "TUN setup crashed: ${e.message}\n${e.stackTraceToString()}")
                throw e
            }

        } catch (e: Exception) {
            Log.e("V2RayVpnService", "Crash!", e)
        }
    }
}
```

### 4️⃣ **بررسی اینکه Native Libraries موجودند:**
```bash
# APK رو extract کن
unzip app-release.apk -d extracted_apk

# ببین کتابخانه‌ها جایی هستند یا نه
ls -la extracted_apk/lib/arm64-v8a/

# باید این دو موجود باشند:
# libxray.so
# libhev-socks5-tunnel.so
```

### 5️⃣ **اگر Native Library مشکل دارد:**
```gradle
// build.gradle.kts - ببین این tasks درست اجرا شده یا نه
tasks.named("preBuild") {
    dependsOn("downloadXrayCore", "downloadHevSocks5Tunnel")
}
```

## 🎯 **پیدا کردن دقیق مشکل:**

اگر:
- ✅ "TUN fd established" می‌بینی اما ❌ "SOCKS5 inbound never came up" 
  → **Xray core خودش مشکل دارد** (config نادرست یا binary خراب)

- ✅ "SOCKS5 inbound came up" اما ❌ "hev-socks5-tunnel loop exited" 
  → **Native library مشکل دارد**

- ✅ صرفاً **crash بدون پیام**
  → **Exception handling ضعیف است**

---

**نتیجه Logcat رو اینجا بفرست تا بتونم بیشتر کمکت کنم!** 📲
