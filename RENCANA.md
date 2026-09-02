# Rencana Modernisasi Java Debug Server

> Dokumen perencanaan migrasi & modernisasi `java-debug` (Java Debug Server untuk VS Code).
> Status: **draf rencana** — belum ada perubahan kode.

---

## 1. Ringkasan

- **Native image (GraalVM) DITUNDA** — tidak dikerjakan sekarang, dipertahankan sebagai fase opsional (lihat Fase 5).
- **Fokus sekarang:** keluar dari **Java 11** (EOL) dan memodernisasi build dari **Tycho/OSGi/p2 → Maven polos** ("plain Maven").
- Strategi: proyek ini **sudah berbasis Maven** (pom + `mvnw` + CI). Migrasi ke plain Maven jauh lebih kecil daripada migrasi ke Gradle — tidak perlu menulis ulang sistem build, cukup memangkas Tycho dan menaikkan versi Java.

---

## 2. Kondisi Saat Ini (Hasil Audit)

| Modul | Packaging | Peran | Level Java |
|---|---|---|---|
| `com.microsoft.java.debug.core` | `jar` | Mesin debug murni JDI + DAP (`ProtocolServer`, `DebugAdapter`, SPI provider) | **Java 11** (pom: source/target 11) |
| `com.microsoft.java.debug.plugin` | `eclipse-plugin` (Tycho) | OSGi bundle, berjalan **di dalam proses jdt.ls**; 13 file mengimpor `org.eclipse.jdt.ls.core` | JavaSE-21 (MANIFEST) |
| `com.microsoft.java.debug.repository` | `eclipse-repository` (Tycho) | Menghasilkan p2 update site (`category.xml`) | — |
| `com.microsoft.java.debug.target` | `eclipse-target-definition` (Tycho) | Target platform: Eclipse 4.38 + jdt.ls snapshot | JavaSE-21 |

**CI saat ini** (`.github/workflows/build.yml`): JDK 21 Temurin, `./mvnw clean verify`, `./mvnw checkstyle:check`.

### Temuan penting

1. **`core` adalah modul paling siap dimodernisasi** — nol dependensi `org.eclipse.*`, murni JDI + DAP dengan SPI provider (`ISourceLookUpProvider`, `IVirtualMachineManagerProvider`, `IHotCodeReplaceProvider`, `IEvaluationProvider`, `ICompletionsProvider`), plus suite uji JUnit 4 + EasyMock.
2. **`plugin` adalah satu-satunya modul yang terikat Tycho/OSGi** — inilah batasan utama. Ia memuat implementasi provider berbasis jdt.ls dan loop `ServerSocket` (`JavaDebugServer`).
3. **Layout `lib/` bersifat load-bearing**: `MANIFEST.MF` memuat `Bundle-ClassPath` yang menunjuk `lib/*.jar` (termasuk `lib/com.microsoft.java.debug.core-0.53.2.jar`). jdt.ls memuat plugin jar ini sebagai bundle OSGi via `initializationOptions.bundles`.
4. **`--limit-modules java.base,java.logging,java.xml,jdk.jdi,java.compiler`** dipakai Tycho saat kompilasi plugin — wajib dipertahankan.

---

## 3. Verifikasi Feasibility (sudah dicek langsung)

Semua dependensi Eclipse/JDT yang dibutuhkan plugin **tersedia sebagai artifact Maven**:

| Bundle | Koordinat | Versi terbaru | Repositori |
|---|---|---|---|
| JDT Core | `org.eclipse.jdt:org.eclipse.jdt.core` | 3.46.0 | Maven Central |
| JDT Launching | `org.eclipse.jdt:org.eclipse.jdt.launching` | 3.24.200 | Maven Central |
| JDT Debug | `org.eclipse.jdt:org.eclipse.jdt.debug` | 3.26.0 | Maven Central |
| JDT Core Manipulation | `org.eclipse.jdt:org.eclipse.jdt.core.manipulation` | 1.24.100 | Maven Central |
| Eclipse Debug Core | `org.eclipse.platform:org.eclipse.debug.core` | 3.23.0 | Maven Central |
| Eclipse Core Runtime | `org.eclipse.platform:org.eclipse.core.runtime` | 3.33.100 | Maven Central |
| LSP4J | `org.eclipse.lsp4j:org.eclipse.lsp4j` | 1.0.0 | Maven Central |
| **jdt.ls core** | `org.eclipse.jdt.ls:org.eclipse.jdt.ls.core` | 1.60.0 / 1.61.0-SNAPSHOT | **repo.eclipse.org** (bukan Central) |
| Gson | `com.google.code.gson:gson` | 2.14.0 | Maven Central |
| commons-lang3 | `org.apache.commons:commons-lang3` | 3.20.0 | Maven Central |
| OSGi framework | `org.osgi:org.osgi.framework` | 1.10.0 | Maven Central |

**Kesimpulan:** jalur plain Maven layak. Satu-satunya bundle yang tidak di Central adalah `org.eclipse.jdt.ls.core`, dan itu tersedia di repositori Maven milik Eclipse (`https://repo.eclipse.org/content/repositories/releases/` / `snapshots/`).

---

## 4. Arah Strategi & Rekomendasi

**Rekomendasi: pertahankan Maven, buang Tycho/OSGi/p2, naikkan Java ke 21 (atau 25).**

Alasan:
- Delta perubahan kecil: `mvnw`, struktur pom, dan CI **tetap**; hanya packaging `eclipse-plugin` yang berubah menjadi `jar` polos.
- Migrasi ke Gradle = penulisan ulang total build (walaupun mendukung native image dengan baik) — tidak sepadan selama native image ditunda.
- Bila kelak native image dilanjutkan, fondasi plain-Maven ini tetap kompatibel (plugin `native-maven-plugin` resmi mendukung Maven).

---

## 5. Roadmap Bertahap

### Fase 0 — Keputusan arsitektur (~1 hari)
- Komitmen scope: apakah path OSGi/jdt.ls tetap menjadi distribusi utama (kemungkinan besar ya).
- Pilih target Java: **21** (LTS, konsisten dengan CI & BREE) atau **25**.

### Fase 1 — Naikkan Java core 11 → 21 (langkah kecil pertama, kemenangan cepat)
- Edit `com.microsoft.java.debug.core/pom.xml`: `source`/`target` 11 → 21.
- Jalankan `./mvnw clean verify` di JDK 21 — pastikan tidak ada API yang dibuang (Java 11→21 umumnya source-compatible).
- **Gate:** suite uji JUnit 4 + EasyMock hijau.

### Fase 2 — Migrasi `plugin` dari `eclipse-plugin` ke `jar` polos (bagian inti)
- Ubah packaging → `jar`; hapus plugin Tycho (`tycho-maven-plugin`, `tycho-compiler-plugin`, `target-platform-configuration`).
- Ganti resolusi target platform dengan dependensi Maven biasa (tabel di §3) + tambah `<repository>` `repo.eclipse.org`.
- Pertahankan:
  - Argumen kompilasi `--limit-modules` (via `maven-compiler-plugin`).
  - `maven-dependency-plugin` penyalinan ke `lib/` (sudah ada di pom).
  - Layout jar akhir: `plugin.xml`, `META-INF/MANIFEST.MF`, `lib/*.jar`, class hasil kompilasi — pastikan `maven-jar-plugin` mengemas MANIFEST & `lib/` dengan benar.
- **Gate:** jar plugin yang dihasilkan setara dengan keluaran Tycho (bisa dibandingkan isinya), dan tetap valid sebagai bundle OSGi.

### Fase 3 — Tangani modul `repository` & `target`
- `target` (`eclipse-target-definition`): keluarkan dari build; file `.target` dipertahankan hanya untuk pengembangan PDE di IDE.
- `repository` (p2 update site): **keputusan terbuka** — (a) hapus, (b) pindahkan ke plugin Maven pembuat p2 (mis. `org.standardout:bnd-platform`), atau (c) biarkan Tycho hanya untuk modul ini (hybrid).

### Fase 4 — CI & pemeriksaan
- Perbarui `.github/workflows/build.yml`: hilangkan `-U` bila perlu, pastikan tetap `./mvnw clean verify` + `checkstyle:check` (plugin Checkstyle Maven **tetap dipakai**, cukup buang bagian Tycho).
- Pertahankan `check_style.xml` + sevntu seperti sekarang.

### Fase 5 — [DITUNDA / OPSIONAL] GraalVM Native Image
Ringkasan rencana saat native image dilanjutkan:
1. **Reframe:** Native Image tidak bisa menghosting OSGi/Equinox → target native adalah **proses standalone** = `core` + `main()` + implementasi provider murni JDI (source lookup berbasis filesystem, classpath dari argumen DAP).
2. **Spike JDI-di-native (gerbang go/no-go):** binary minimal yang meluncurkan debuggee dengan `-agentlib:jdwp`, koneksi via `SocketAttachingConnector`, pasang breakpoint, baca event. (Attach API berbasis PID tidak didukung; konektor socket — yang dipakai java-debug — umumnya berfungsi.)
3. Integrasi build: `native-maven-plugin` (Maven) atau `org.graalvm.buildtools.native` (Gradle) + metadata reachability (agent tracing) untuk gson/rxjava2.
4. Menutup celah provider: evaluasi (ecj sebagai library, `org.eclipse.jdt:ecj`), HCR (`redefineClasses()`), completions.
5. Integrasi peluncuran ada di luar repo ini (vscode-java / eclipse.jdt.ls harus mengeksekusi binary native sebagai proses eksternal).

---

## 6. Risiko & Mitigasi

| Risiko | Dampak | Mitigasi |
|---|---|---|
| `org.eclipse.jdt.ls.core` tidak di Maven Central | Blocker resolusi dependensi | Gunakan `repo.eclipse.org` (sudah terverifikasi ada) |
| Layout `lib/` + `Bundle-ClassPath` rusak | Jar plugin gagal dimuat jdt.ls | Urutkan penyalinan `lib/` **sebelum** packaging; jangan biarkan Maven menulis ulang MANIFEST |
| `--limit-modules` hilang | Runtime error di dalam jdt.ls (JPMS) | Replikasi argumen secara eksplisit di `maven-compiler-plugin` |
| Drift versi API jdt.ls (snapshot vs release) | Compile/runtime error | Pin versi **release** dari repo.eclipse.org bila memungkinkan |
| Modul `repository` (p2) tanpa pengganti | Kehilangan update site | Putuskan di Fase 3; opsi `bnd-platform` tersedia |
| Fitur berbasis jdt.ls (evaluasi, completions, classpath) | Hanya ada di path legacy | Pertahankan path OSGi sebagai distribusi utama selama transisi |

---

## 7. Kriteria Sukses

- [ ] `./mvnw clean verify` hijau di **JDK 21** tanpa plugin Tycho.
- [ ] Semua modul terkompilasi pada **Java ≥ 21**.
- [ ] Jar plugin setara dengan keluaran Tycho (isi jar, MANIFEST, `lib/`) dan tetap valid sebagai bundle OSGi.
- [ ] `./mvnw checkstyle:check` hijau (tidak berubah).

---

## 8. Keputusan Terbuka (menunggu konfirmasi)

1. **Target Java**: 21 (LTS, aman) atau 25 (terbaru)?
2. **Modul `repository` (p2 update site)**: dihapus, dipindah (bnd-platform), atau dipertahankan hybrid?
3. **Distribusi utama**: apakah bundle OSGi untuk jdt.ls tetap menjadi satu-satunya distribusi resmi (sehingga MANIFEST & layout `lib/` wajib dipertahankan)?
