# Step-by-Step: Menghapus Tycho dari Build (Tycho → Plain Maven)

> Dokumen panduan eksekusi untuk mengeluarkan Tycho dari proyek `java-debug`.
> Pendamping dari `RENCANA.md` (Fase 2 & 3).
> Semua perubahan hanya di **2 file pom** + menghapus **2 file pom**. `core`, `mvnw`, checkstyle, dan CI tidak berubah.

---

## Step 0 — Keamanan (Backup)

```bash
git status          # pastikan working tree bersih
git checkout -b feat/drop-tycho
```

---

## Step 1 — Root `pom.xml` (3 penghapusan)

### 1a. Hapus property ini

```xml
<tycho-version>5.0.0</tycho-version>
```

### 1b. Hapus 2 module dari `<modules>`

```xml
<module>com.microsoft.java.debug.repository</module>
<module>com.microsoft.java.debug.target</module>
```

### 1c. Hapus 2 blok plugin

- Hapus **seluruh blok** `target-platform-configuration` di dalam `<pluginManagement>`.
- Hapus **seluruh blok** `tycho-maven-plugin` di dalam `<plugins>` (biarkan `<plugins/>` kosong).

---

## Step 2 — `com.microsoft.java.debug.plugin/pom.xml` (konversi inti)

Ganti seluruh isi file dengan:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.microsoft.java</groupId>
        <artifactId>java-debug-parent</artifactId>
        <version>0.53.2</version>
    </parent>
    <artifactId>com.microsoft.java.debug.plugin</artifactId>
    <packaging>jar</packaging>
    <name>${base.name} :: Debugger Plugin</name>

    <repositories>
        <repository>
            <id>repo.eclipse.org</id>
            <url>https://repo.eclipse.org/content/repositories/releases/</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>org.eclipse.jdt</groupId>
            <artifactId>org.eclipse.jdt.core</artifactId>
            <version>3.46.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse.jdt</groupId>
            <artifactId>org.eclipse.jdt.launching</artifactId>
            <version>3.24.200</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse.jdt</groupId>
            <artifactId>org.eclipse.jdt.debug</artifactId>
            <version>3.26.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse.jdt</groupId>
            <artifactId>org.eclipse.jdt.core.manipulation</artifactId>
            <version>1.24.100</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.debug.core</artifactId>
            <version>3.23.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse.platform</groupId>
            <artifactId>org.eclipse.core.runtime</artifactId>
            <version>3.33.100</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse.jdt.ls</groupId>
            <artifactId>org.eclipse.jdt.ls.core</artifactId>
            <version>1.60.0.20260626225408</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.osgi</groupId>
            <artifactId>org.osgi.framework</artifactId>
            <version>1.10.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <resources>
            <resource>
                <directory>${project.basedir}</directory>
                <includes>
                    <include>plugin.xml</include>
                    <include>lib/**</include>
                </includes>
            </resource>
        </resources>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>21</release>
                    <compilerArgs>
                        <arg>--limit-modules</arg>
                        <arg>java.base,java.logging,java.xml,jdk.jdi,java.compiler</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.2</version>
                <configuration>
                    <archive>
                        <manifestFile>${project.basedir}/META-INF/MANIFEST.MF</manifestFile>
                    </archive>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <configuration>
                    <artifactItems>
                        <artifactItem>
                            <groupId>io.reactivex.rxjava2</groupId>
                            <artifactId>rxjava</artifactId>
                            <version>2.2.21</version>
                        </artifactItem>
                        <artifactItem>
                            <groupId>org.reactivestreams</groupId>
                            <artifactId>reactive-streams</artifactId>
                            <version>1.0.4</version>
                        </artifactItem>
                        <artifactItem>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-io</artifactId>
                            <version>2.19.0</version>
                        </artifactItem>
                        <artifactItem>
                            <groupId>com.microsoft.java</groupId>
                            <artifactId>com.microsoft.java.debug.core</artifactId>
                            <version>0.53.2</version>
                        </artifactItem>
                    </artifactItems>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### Yang sengaja dipertahankan

- `--limit-modules java.base,java.logging,java.xml,jdk.jdi,java.compiler` — persis seperti argumen Tycho (plugin berjalan di dalam jdt.ls/JPMS).
- `manifestFile` → `META-INF/MANIFEST.MF` yang sudah ada — `Bundle-ClassPath` dan layout `lib/` tetap utuh.
- `<include>lib/**</include>` + `plugin.xml` → ikut terpaket ke dalam jar.
- `maven-dependency-plugin` → tetap menyalin dependensi runtime ke `lib/`.
- Scope `provided` → bundle Eclipse hanya untuk kompilasi; saat runtime disediakan oleh jdt.ls (sesuai semantik `Require-Bundle`).

---

## Step 3 — Hapus pom Tycho-only

Module `repository` dan `target` sudah tidak dibangun (dikeluarkan dari `<modules>` di Step 1b). Hapus pom-nya:

```bash
rm com.microsoft.java.debug.repository/pom.xml
rm com.microsoft.java.debug.target/pom.xml
```

> Direktori tetap ada — `category.xml`, file `.target`, dan script tetap tersimpan. Git menyimpan history pom-nya jika ingin kembali.

---

## Step 4 — Build

```bash
./mvnw clean verify
```

---

## Step 5 — Verifikasi hasil

```bash
unzip -l com.microsoft.java.debug.plugin/target/com.microsoft.java.debug.plugin-0.53.2.jar
```

Harus berisi: `plugin.xml`, `META-INF/MANIFEST.MF`, `lib/rxjava-2.2.21.jar`, `lib/com.microsoft.java.debug.core-0.53.2.jar`, dst., dan class hasil kompilasi.

Cek MANIFEST-nya masih memuat `Bundle-ClassPath` dan `Require-Bundle`:

```bash
unzip -p com.microsoft.java.debug.plugin/target/com.microsoft.java.debug.plugin-0.53.2.jar META-INF/MANIFEST.MF
```

---

## Troubleshooting: Error kompilasi di Step 4

Penyebab paling umum: **drift versi API jdt.ls** — Tycho sebelumnya memakai snapshot terbaru dari p2, sekarang Maven memakai release `1.60.0`.

Cek versi snapshot yang tersedia:

```bash
curl -s https://repo.eclipse.org/content/repositories/snapshots/org/eclipse/jdt/ls/org.eclipse.jdt.ls.core/maven-metadata.xml
```

Lalu ganti `<version>1.60.0.20260626225408</version>` di pom plugin dengan versi snapshot yang cocok (atau pin versi `org.eclipse.jdt.*` lainnya), dan ulangi `./mvnw clean verify`. Biasanya cukup 1-2 iterasi.

---

## Opsional — Escape Java 11 (tujuan utama)

Di `com.microsoft.java.debug.core/pom.xml`, pada `maven-compiler-plugin`:

```xml
<!-- sebelum -->
<source>11</source>
<target>11</target>

<!-- sesudah -->
<release>21</release>
```

Lalu build ulang:

```bash
./mvnw clean verify
```
