# Báo Cáo Điều Tra Kỹ Thuật: Cơ Chế Gửi/Nhận Dữ Liệu Cảnh Báo với `backend.wynauto.com` (Wyn Auto v2.2.1)

---

## 1. Tổng Quan Kiến Trúc Mạng & Xác Thực (Network & Auth Architecture)

Qua quá trình decompile và phân tích chuỗi tệp tin nhị phân trong APK (`classes4.dex`, `lib/arm64-v8a/libapp.so`, `lib/arm64-v8a/libwynlib.so`), hệ thống giao tiếp mạng của Wyn Auto v2.2.1 sử dụng mô hình **V2 Ticket & ECDH Key Exchange** kết hợp với **H3 Spatial Indexing (Uber H3)** và **App Check / Device Proof**:

- **Domain Backend Chính**: `https://backend.wynauto.com`
- **Domain Kích hoạt / License**: `https://qonversion-proxy.wynauto.com`
- **Domain Phân tích / Kích hoạt Thiết bị**: `https://rev-px.wynauto.com`
- **Giao thức Bảo mật Cảnh báo**: Vé dữ liệu Wyn Ticket V2 (`wyn_v2_ticket`, `key_ticket_v2.rs`) trao đổi khóa qua ECDH (`wyn_device_ecdh`) + Chữ ký số niêm phong thiết bị (`wyn_device_seal`).

---

## 2. Danh Sách Các API Endpoints trên `backend.wynauto.com`

### A. Nhóm API Tải Dữ liệu Cảnh báo Giao thông & Bản đồ (Data & Maps):

1. **`GET /v1/user/traffic/live/live-traffic-latest.json`**
   - **Mục đích**: Tải danh sách các điểm cảnh báo giao thông trực tuyến (Live Traffic), vị trí camera phạt nguội mới cập nhật, tình trạng tắc đường real-time.
2. **`GET /v1/wyn-data/latest.json`**
   - **Mục đích**: Kiểm tra phiên bản gói dữ liệu giao thông toàn quốc mới nhất (`get_data_format_version`, `get_data_release_note`, `get_data_timestamp_min`).
3. **`GET /v1/maps/assets/style-`**
   - **Mục đích**: Tải các tệp cấu hình Style JSON bản đồ MapLibre bổ sung và biểu tượng icon biển báo mới.
4. **`POST /v1/data-ticket`** & **`POST /v1/data-ticket/proof`**
   - **Mục đích**: Gửi vé xác thực thiết bị (Device Proof) để gia hạn và cấp quyền tải gói dữ liệu biển báo / camera phạt nguội đầy đủ (`wyn_database_renew_ticket`).

---

### B. Nhóm API Trợ lý Giọng nói & Tra cứu Phạt nguội (Voice & Violation):

1. **`POST /v1/user/traffic-assistant/chat`**
   - **Mục đích**: Gửi câu thoại giọng nói của người lái lên server AI trợ lý giao thông để phân tích ý định (Hỏi về tình trạng kẹt xe, vị trí cây xăng, phạt nguội...).
2. **`POST /v1/user/violation-lookup`**
   - **Mục đích**: Tra cứu thông tin phạt nguội theo biển số xe ô tô từ cơ sở dữ liệu Cục Cảnh sát Giao thông.

---

### C. Nhóm API Xác thực & Quản lý Tài khoản (Auth & Account):

1. **`POST /v1/anonymous/app-check`**
   - **Mục đích**: Xác thực tính chính chủ của thiết bị Android / màn hình TS8 mà không cần đăng nhập.
2. **`POST /v1/auth/login`**
   - **Mục đích**: Đăng nhập tài khoản người dùng Wyn Auto.
3. **`POST /v1/user/contribute/image`** & **`POST /v1/user/reports`**
   - **Mục đích**: Người dùng đóng góp hình ảnh biển báo mới / camera mới phát hiện trên đường.

---

## 3. Cấu Trúc Header & Payload Gửi Lên Server (Request Specification)

### A. Các Headers Bắt buộc trong Request:

```http
POST /v1/data-ticket/proof HTTP/1.1
Host: backend.wynauto.com
User-Agent: WynAuto/2.2.1 (Android; ARM64)
Authorization: Bearer <accessToken>
X-Wyn-Device-Key: <wyn_device_key_b64>
X-Wyn-Device-Proof: <device_proof_signature>
X-Wyn-Ticket-V2: <wyn_v2_ticket>
Content-Type: application/json
Accept: application/json
```

---

### B. Cấu trúc Payload Cung cấp Thông tin Thiết bị (`DeviceCapPayload`):

Khi ứng dụng gọi `/v1/anonymous/app-check` hoặc `/v1/data-ticket/proof`, thông tin phần cứng thiết bị được gửi dưới dạng JSON / Protobuf:

```json
{
  "hardware_revision": "TS8-SP9832A",
  "firmware_version": "2.2.1",
  "display_width_px": 1024,
  "display_height_px": 600,
  "device_proof": "Signature_ECDH_Hash...",
  "supported_groups": ["vnsigns", "vnhazard", "vntoll", "cam_speed"]
}
```

---

## 4. Cơ Chế Native Tính Toán Cảnh Báo Không Gian (Native Rust Core in `libwynlib.so`)

Bên trong thư viện Native `libwynlib.so` (xây dựng bằng Rust `src/key_ticket_v2.rs`), ứng dụng sử dụng các hàm Native FFI (`flutter_rust_bridge`):

1. **Chuyển đổi GPS sang Ô lưới H3 Index**:
   - `frbgen_wyn_wire__crate__api__api__h3_lat_lng_to_cell(lat, lng)`
   - `frbgen_wyn_wire__crate__api__api__h3_lat_lng_to_wgeo_index(lat, lng)`
   - Vị trí GPS của xe `(lat, lon)` được chuyển đổi thành mã ô không gian H3 Index.

2. **Dò điểm Biển báo & Tốc độ gần nhất**:
   - `frbgen_wyn_wire__crate__api__api__probe_sl(lat, lng, heading)` -> Dò tốc độ giới hạn (Speed Limit Probe).
   - `frbgen_wyn_wire__crate__api__api__probe_ss_list(lat, lng, heading)` -> Dò danh sách biển báo (Street Sign List Probe).

3. **Tính Góc hướng & Khoảng cách chính xác**:
   - `frbgen_wyn_wire__crate__api__api__util_cal_bearing_precise(lat1, lon1, lat2, lon2)`
   - `frbgen_wyn_wire__crate__api__api__util_cal_distance(lat1, lon1, lat2, lon2)`

---

## 5. Tóm Tắt Quy Trình Nhận Dữ Liệu Cảnh Báo

```text
┌────────────────────────────────┐
│  Thực hiện App Check / Auth    │
│  /v1/anonymous/app-check       │
└───────────────┬────────────────┘
                │ Cấp Token & Ticket V2
                ▼
┌────────────────────────────────┐
│  Tải Dữ liệu Cảnh báo Mới      │
│  /v1/user/traffic/live/        │
│  live-traffic-latest.json      │
└───────────────┬────────────────┘
                │ Nạp tệp JSON & .bin vào bộ nhớ
                ▼
┌────────────────────────────────┐
│  Dò Tốc độ & Biển báo GPS      │
│  probe_sl() & probe_ss_list()  │
└───────────────┬────────────────┘
                │ Phát hiện biển báo < 300m
                ▼
┌────────────────────────────────┐
│  Phát Âm thanh & Vẽ Marker     │
│  assets/sounds/vi/camera.mp3   │
└────────────────────────────────┘
```
