# Bản đồ Việt Nam (.mwm) — dữ liệu 261119 và cách app tự tải

Cập nhật: 21/11/2026. Thay cho các URL trong `12_VIETNAM_MAP_BUILD.md` (đã chết — xem mục cuối).

## Nguồn dữ liệu

- Mirror: `https://omaps.webfreak.org/maps/261119/` (mirror cộng đồng do chính organicmaps.app giới thiệu)
- Phiên bản dữ liệu: **261119** = build 2026-11-19 00:59 UTC (giải mã từ header `.mwm`), format **v11**
- Dữ liệu OSM tại thời điểm đó; dump Geofabrik hiện tại đã tới 2026-11-21 01:00 UTC (chênh ~2 ngày)

| File | Kích thước | SHA-256 |
|---|---|---|
| `Vietnam_North.mwm` | 152.285.229 B | `333D4D16403721ECDD9A49E33163FD73239DBF28A175F658CD10BC6030180AAF` |
| `Vietnam_South.mwm` | 127.866.690 B | `84079EF93AD581BB56A865B80FF3CD8F821D1ECE51A92FFA49AA3883265D5C9F` |

Hash đầy đủ: `SHA256SUMS.txt`. `countries-261119.json` là hierarchy gốc của bản 261119 (đã dùng
để thay `data/countries.txt` trong app).

## App đã được sửa để tự tải (3 chỗ)

| File | Thay đổi |
|---|---|
| `data/countries.txt` | Thay bằng `countries.json` của bản 261119: `"v": 261119`, có node group `Vietnam` chứa `Vietnam_North` + `Vietnam_South` với `"s"` khớp đúng kích thước thật. File này là **hardlink** với `android/sdk/src/main/assets/countries.txt`, nên phải ghi đè tại chỗ (đã làm) |
| `private.h` | `METASERVER_URL` → `""` (để `LoadMetaConfig()` bỏ qua metaserver và fallback về danh sách tĩnh); `DEFAULT_URLS_JSON` → `["https://omaps.webfreak.org/"]` |
| `storage/storage.cpp` | Bỏ qua kiểm SHA1 khi countries file không có `sha1_base64`. File format mới chỉ có tag ngắn `"h"`, không phải sha1 — nếu vẫn so sánh thì mọi lần tải đều bị xoá với `FailedSHA` |

URL app sẽ gọi: `https://omaps.webfreak.org/maps/261119/Vietnam_North.mwm` (và `..._South.mwm`),
đúng layout mà `storage/downloader_utils.cpp` sinh ra và đúng đường dẫn mirror đang phục vụ.

## Cài đặt

```powershell
adb install -r OrganicMaps-25071301-ts8lite-release.apk
# xoá bản đồ cũ (nếu máy còn Vietnam.mwm của bản trước)
adb shell rm -f /sdcard/Android/data/app.organicmaps.ts8/files/Vietnam.mwm
```

Mở app → màn hình tải bản đồ → **Việt Nam** → tải (gồm 2 mục North/South).

## Giới hạn cần biết

- `data/packed_polygons.bin` vẫn là bản 250708 (Việt Nam là một leaf). Native test
  `CountryInfoGetter_Countries_And_Polygons` sẽ fail nếu chạy; tra cứu "vùng nào chứa điểm này"
  có thể trả về `Vietnam` (node group) thay vì North/South. Không ảnh hưởng hiển thị/dẫn đường.
- Kiểm tra toàn vẹn SHA khi tải hiện bị bỏ (xem bảng trên). Kích thước vẫn được đối chiếu, và
  mirror đi qua HTTPS.
- `World.mwm` nhúng trong APK là bản 250708 (50.707.935 B); bản 261119 là 56.458.735 B nên app
  có thể đề nghị tải World mới (~56 MB) từ mirror trong lần đầu.
- Chưa kiểm chứng runtime: AVD `TS8_Android6` (x86_64) thiếu system image nên không boot được,
  và không có máy thật arm32. Luồng bấm tải trong app **chưa được chạy thực tế**.

## Vì sao không dùng URL trong guide

`download.organicmaps.app` không còn phân giải; `cdn.organicmaps.app/maps/<v>/` trả 404;
`data_version.json` chỉ còn `[{"22020701": 220129}]` (bản 2022). Trang chủ CDN hiện chỉ liệt kê
mirror cộng đồng. Metaserver `meta.omaps.app/maps` trả `cdn-us1.organicmaps.app` — host này cũng
không có file map, nên app cũ không thể tải dù đổi URL.
