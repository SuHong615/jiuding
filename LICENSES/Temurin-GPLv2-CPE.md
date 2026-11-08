# Eclipse Temurin (GPLv2 + Classpath Exception)

本项目 Docker 基础镜像使用 **Eclipse Temurin 17 JRE**（`eclipse-temurin:17-jre`）。

- 许可证：GNU General Public License v2.0 with Classpath Exception（GPLv2+CE）
- 许可证文本：https://openjdk.org/legal/gplv2+ce.html
- 源码获取：https://github.com/adoptium/temurin17-binaries
（亦可通过 Adoptium 的 Release 页面获取对应版本源码）

## 分发注意事项

1. 若你分发包含 Temurin 的 Docker 镜像或二进制包，需按 GPLv2+CE 提供相应源码获取方式（上述链接）。
2. Classpath Exception 允许专有应用以 Classpath 方式链接 GPL 代码而不受 GPL 传染；本项目自研代码采用 Apache-2.0，与 Temurin 的链接属于 Classpath Exception 覆盖范围。
3. 不要移除镜像内 Temurin 的版权与许可文件。
