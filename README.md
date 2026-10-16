<p align="center">
  <img src="docs/icon.png" width="128" alt="WireguardXT" />
</p>

<h1 align="center">WireguardXT</h1>

<p align="center">Ứng dụng VPN WireGuard / AmneziaWG cho điện thoại và máy tính bảng Android (và Android TV),<br/>
dựa trên mã nguồn mở <a href="https://github.com/wgtunnel/android">WG Tunnel</a>, bổ sung tính năng <b>tự giám sát máy chủ</b>.</p>

---

## Tính năng

### Toàn bộ tính năng của WG Tunnel (5.8.1)
- Nhập cấu hình từ file `.conf`, file zip, mã QR, URL hoặc tạo thủ công; xuất cấu hình
- Hỗ trợ WireGuard và **AmneziaWG** (chống DPI), multihop (tunnel vào → tunnel ra)
- **Tự động bật/tắt tunnel (Auto-tunnel)** theo Wi-Fi (SSID/BSSID, ký tự đại diện), dữ liệu di động, Ethernet
- Chế độ **VPN**, **Proxy** (SOCKS5/HTTP) và **Lockdown/Kill switch**
- Chia tách ứng dụng (split tunneling) theo từng tunnel hoặc toàn cục
- Tự phục hồi kết nối (seamless recovery), DDNS khi bắt tay thất bại, ưu tiên IPv6 / quay về IPv4
- DNS tuỳ chỉnh (DoH/DoT), thống kê lưu lượng trực tiếp, nhật ký
- Ô Cài đặt nhanh (Quick Settings), lối tắt, điều khiển từ xa qua Intent (Tasker…)
- Khoá ứng dụng bằng PIN, sao lưu/khôi phục, hỗ trợ Always-on VPN, khởi động cùng máy
- Giao diện Material 3 tối ưu cho cả điện thoại, **máy tính bảng** và Android TV

### Bổ sung trong WireguardXT — *Cài đặt › WireguardXT › Giám sát máy chủ*

**1. Tự cập nhật IP máy chủ khi IP thay đổi**
- Định kỳ (1–30 phút) tra cứu lại tên miền máy chủ **bên ngoài tunnel** (DNS của mạng thật + DNS-over-HTTPS Cloudflare/Google).
- Khi máy chủ khởi động lại và nhận IP mới, WireguardXT tự chuyển tunnel sang IP mới:
  thử cập nhật peer tại chỗ (không ngắt kết nối), nếu chưa được thì khởi động lại tunnel với IP mới
  (ghim tạm 15 phút để không bị bộ nhớ đệm DNS cũ cản trở).
- Với cấu hình có **Endpoint là địa chỉ IP cố định**: gán cho tunnel một **tên miền DDNS** (vd. `nhacuatoi.duckdns.org`)
  hoặc **URL trả về IP** — khi nguồn này đổi IP, Endpoint trong cấu hình được **ghi lại IP mới** tự động.
- Kiểm tra ngay lập tức khi tunnel bắt đầu bắt tay thất bại (thường là lúc máy chủ vừa khởi động lại).
- An toàn với mạng LAN dùng DNS nội bộ (split-horizon): chỉ coi là "đổi IP" khi IP đang dùng không còn nằm trong bất kỳ kết quả tra cứu nào.

**2. Tạm ngắt VPN khi máy chủ bị cúp điện — để không mất Internet**
- Khi tunnel bắt tay thất bại liên tục quá thời gian đặt (45 giây – 5 phút) **trong khi Internet của máy vẫn hoạt động**
  (được kiểm tra trực tiếp bên ngoài tunnel), WireguardXT xác định máy chủ đang mất kết nối và **tạm tắt VPN**.
- Ở chế độ Lockdown, Kill switch được **tạm bỏ** để vẫn truy cập được Internet (tuỳ chọn), và bật lại khi VPN kết nối lại.
- Một dịch vụ nền tiếp tục **kiểm tra máy chủ** (1–15 phút một lần, hoặc ngay khi bạn mở khoá màn hình/đổi mạng):
  - *Thử kết nối VPN*: bật VPN vài giây chờ bắt tay — chính xác nhất.
  - *Ping máy chủ* (ICMP hoặc cổng TCP tuỳ chọn): không làm gián đoạn Internet.
- Khi máy chủ có điện trở lại, VPN **tự kết nối lại** và thông báo cho bạn. Có nút *Kết nối lại ngay* / *Hủy chờ* trên thông báo.
- Không tạm ngắt khi điện thoại mất mạng, đang ở chế độ ngủ sâu (Doze) hay bị cổng đăng nhập Wi-Fi chặn.
- Trạng thái tạm ngắt được lưu lại, vẫn hoạt động sau khi khởi động lại máy.

Mọi sự kiện (đổi IP, tạm ngắt, kết nối lại) được ghi vào **Nhật ký** trong màn hình Giám sát máy chủ.

## Tải và cài đặt

### Cách 1 — Build tự động bằng GitHub Actions (không cần cài gì trên máy)
1. Tạo một repository mới trên GitHub và đẩy toàn bộ thư mục này lên (nhánh `main`).
2. Vào tab **Actions** → workflow **Build WireguardXT APK** sẽ tự chạy (hoặc bấm *Run workflow*).
3. Sau ~10–15 phút, tải file APK trong mục **Artifacts** của lần chạy đó.
   - `wireguardxt-fdroid-v1.0.0.apk` (universal, dùng cho mọi máy), hoặc bản `-arm64` / `-armv7` nhỏ hơn.
4. Gắn tag `v1.0.0` (`git tag v1.0.0 && git push --tags`) để tạo bản phát hành (Release) có sẵn APK.

**Khoá ký cố định (nên làm một lần):** để cài bản mới đè lên bản cũ mà không mất dữ liệu, tạo khoá ký:
```bash
keytool -genkeypair -v -keystore wireguardxt.jks -alias wireguardxt -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 wireguardxt.jks   # sao chép kết quả
```
Rồi thêm vào *Settings › Secrets and variables › Actions*: `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` (= `wireguardxt`), `SIGNING_KEY_PASSWORD`. Nếu không có, mỗi lần build dùng khoá tạm.

### Cách 2 — Build bằng Android Studio
- Mở thư mục dự án bằng Android Studio (bản mới nhất), JDK 21.
- Bản thử: chọn biến thể `fdroidDebug` → Run (ứng dụng tên *WireguardXT Debug*).
- Bản phát hành: tạo `app/keystore/wireguardxt.jks` và khai báo trong `local.properties`:
  ```properties
  SIGNING_STORE_PASSWORD=...
  SIGNING_KEY_ALIAS=wireguardxt
  SIGNING_KEY_PASSWORD=...
  ```
  rồi chạy `./gradlew :app:assembleFdroidRelease`.

Yêu cầu: Android 8.0 (API 26) trở lên. Mã ứng dụng: `com.wireguardxt.app` — cài song song được với WG Tunnel.

## Hướng dẫn nhanh tính năng mới
1. Nhập cấu hình WireGuard như bình thường.
2. Nếu Endpoint của bạn là **tên miền DDNS**: không cần làm gì thêm — tính năng tự cập nhật IP bật sẵn.
3. Nếu Endpoint là **IP**: vào *Cài đặt › Giám sát máy chủ › Nguồn IP theo từng tunnel*, chạm tunnel và nhập tên miền DDNS
   của router/máy chủ (DuckDNS, No-IP, ASUS DDNS…) hoặc URL trả về IP.
   Nếu máy chủ không có DDNS thì không có cách nào để điện thoại biết IP mới — hãy bật DDNS trên router.
4. Bật **Cho phép chạy nền không giới hạn** (dòng màu đỏ nếu chưa bật) để ứng dụng tự kết nối lại khi máy chủ có điện.
5. Bấm **Kiểm tra ngay** để xem IP mà máy chủ đang trỏ tới.

> Lưu ý: nếu bạn bật *VPN luôn bật + Chặn kết nối không dùng VPN* trong cài đặt Android, chính Android sẽ chặn Internet
> khi VPN tạm ngắt. Hãy dùng chế độ Lockdown của ứng dụng thay cho tuỳ chọn hệ thống nếu muốn có Kill switch.

## Cấu trúc mã nguồn bổ sung
| Tệp | Vai trò |
|---|---|
| `app/src/main/java/.../xt/ServerWatchdogCoordinator.kt` | Logic giám sát: tra IP, phát hiện mất kết nối, tạm ngắt, kiểm tra và tự nối lại |
| `.../xt/XtNetwork.kt` | Tra DNS / DoH, kiểm tra Internet, ping/TCP — luôn đi qua mạng vật lý, không qua tunnel |
| `.../xt/XtEndpointOverrides.kt` | Ghim IP mới tạm thời khi khởi động tunnel |
| `.../xt/ServerWatchdogService.kt` | Dịch vụ nền giữ ứng dụng hoạt động khi VPN đang tạm ngắt |
| `.../xt/XtSettingsRepository.kt`, `XtModels.kt`, `XtIp.kt` | Cài đặt, trạng thái, nhật ký, tiện ích IP |
| `.../ui/screens/settings/watchdog/ServerWatchdogScreen.kt` | Màn hình cài đặt *Giám sát máy chủ* |

## Giấy phép và ghi công
WireguardXT là bản phái sinh của **WG Tunnel** © 2023-2025 Zane Schepke, phát hành theo giấy phép **MIT** (xem `LICENSE`).
Các phần bổ sung của WireguardXT cũng theo giấy phép MIT.

"WireGuard" là nhãn hiệu đã đăng ký của Jason A. Donenfeld. WireguardXT không phải sản phẩm chính thức của dự án WireGuard
hay WG Tunnel. Biểu tượng của WireguardXT là thiết kế riêng.
