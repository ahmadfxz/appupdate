# appupdate — paksa update aplikasi Android

Modul Android yang memblokir aplikasi sampai user update, berdasarkan jawaban API [appupdate-backend](https://github.com/ahmadfxz/appupdate-backend) (default `https://dev.kerja.online`).

Mandiri: tanpa dependensi pihak ketiga, tanpa version catalog, tidak tahu apa-apa tentang aplikasi host. minSdk 21.

## Pasang di aplikasi

Lewat [JitPack](https://jitpack.io) — ganti `ahmadfxz` dengan akun GitHub pemilik repo ini dan `1.0.3` dengan tag rilis.

1. `settings.gradle.kts`:
   ```kotlin
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven { url = uri("https://jitpack.io") }
       }
   }
   ```
2. `app/build.gradle.kts`:
   ```kotlin
   implementation("com.github.ahmadfxz:appupdate:1.0.3")
   ```
3. `Application.onCreate()`:
   ```kotlin
   AppUpdate.install(this)                                  // package = applicationId
   AppUpdate.install(this, packageName = "live.app")        // kunci tetap (mis. build .debug)
   AppUpdate.install(this, baseUrl = "https://api.lain.com") // server lain
   ```

Butuh Kotlin 2.0+ dan minSdk 21+. Tidak menarik dependensi selain kotlin-stdlib.

## Rilis versi baru

```sh
git tag 1.0.4 && git push origin 1.0.4
```

JitPack mem-build tag itu saat pertama kali diminta (status build: `https://jitpack.io/#ahmadfxz/appupdate`). Tes lokal: `./gradlew :appupdate:publishReleasePublicationToMavenLocal`.

## Perilaku

- Cek ke server setiap aplikasi masuk foreground, mengirim versionCode yang terpasang.
- `need_update=true` → seluruh layar aplikasi diganti layar "Update Diperlukan" yang hanya berisi tombol membuka `update_url` (kosong → Play Store). Tombol back mengirim aplikasi ke background, tidak kembali ke dalam aplikasi; membuka lewat launcher / notifikasi / deep link tetap diblokir.
- Status disimpan, jadi mematikan internet tidak membuka blokir. Blokir lepas bila server menjawab `false`/404, atau versionCode terpasang berubah (user sudah update).
- Server mati / error jaringan **tidak** memblokir aplikasi.
- Teks bisa diganti dari aplikasi host dengan mendefinisikan ulang string `appupdate_title`, `appupdate_message`, `appupdate_button`.
