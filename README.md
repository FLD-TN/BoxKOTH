# ClanKoth (BoxKOTH)

Sự kiện chiếm vùng (King of the Hill) theo bang hội, dùng clan của BoxClans. Trong trận, mỗi lúc chỉ có một người giữ
vùng; bang của người đó được cộng thời gian chiếm. Hết giờ, 5 bang có thời gian chiếm lâu nhất được thưởng.

Paper 1.21.1, Java 21. Cần BoxClans, PlaceholderAPI và DecentHolograms. Tên plugin trong server là `ClanKoth`.

## Luật chiếm vùng

- Vùng là một khối hộp chọn bằng `/koth pos1` và `/koth pos2`.
- Người có clan bước vào vùng trống sẽ thành người giữ vùng. Người đó còn đứng trong vùng và chưa đổi clan thì vẫn
  giữ, dù người bang khác cũng đứng trong đó.
- Người giữ rời vùng hoặc chết thì chọn người mới trong số những người đang ở trong vùng, theo `selection-mode`:
  `RANDOM` (ngẫu nhiên) hoặc `LONGEST_INSIDE` (người đứng lâu nhất).
- Mỗi tick người giữ vùng đứng trong đó, bang của họ được cộng thời gian. Trận dài theo giờ thật nên server lag không
  kéo dài trận.
- Trong lúc có KOTH, người chơi không tạo, giải tán, rời, vào, kick, mời hay chuyển chủ clan được (`clan-lock`). Lệnh
  admin của BoxClans không bị chặn.

## Phần thưởng (`rewards.yml`)

Mỗi hạng từ 1 đến 5 có 3 nhóm lệnh console:

- `leader`: Minh Chủ. Minh Chủ offline thì Phó Bang online nhận thay (`vice-stands-in-for-leader`).
- `members`: thành viên online lúc kết thúc, trừ người vào clan giữa trận. Có thể giới hạn 1 tài khoản mỗi IP.
- `mvp`: thành viên giữ vùng lâu nhất cho bang.

Người offline lúc kết thúc không nhận gì. Bật `clan-points` thì top 1 đến 3 còn được điểm bang hội BoxClans (nguồn
`koth-top1`..`koth-top3`). Placeholder dùng được trong lệnh: `%player%`, `%clan%`, `%clan_tag%`, `%clan_display%`,
`%rank%`, `%zone%`, `%clan_time%`, `%player_time%`.

## Hiển thị

- Bossbar đếm giờ và bang đang dẫn đầu, title lúc bắt đầu, âm thanh khi đổi người giữ vùng.
- Người giữ vùng phát sáng (`controller-glow`).
- Hai hologram cho mỗi vùng: bảng xếp hạng top 5 và bảng thông tin (trận kế tiếp, còn bao lâu).
- Thông báo đổi người giữ vùng hiện ở chat, actionbar hoặc tắt.

## Lịch

`scheduler.schedules` trong `config.yml`: vùng, giờ trong ngày, các ngày trong tuần, theo `timezone`. Plugin nhắc trước
khi bắt đầu (mặc định từ 30 phút trước, 5 phút một lần) và đếm ngược 60 và 10 giây.

## Lệnh

| Lệnh | Quyền | Mô tả |
|---|---|---|
| `/koth`, `/koth time` | `clankoth.player` | Xem các trận sắp tới |
| `/koth start <vùng>` | `clankoth.start` | Bắt đầu trận |
| `/koth stop <vùng> [--no-rewards]` | `clankoth.start` | Dừng trận, có hoặc không phát thưởng |
| `/koth schedule`, `/koth list`, `/koth info <vùng>` | `clankoth.start` | Lịch, danh sách vùng, thông tin vùng |
| `/koth pos1`, `/koth pos2`, `/koth create <tên>` | `clankoth.admin` | Chọn góc và tạo vùng |
| `/koth delete <vùng>` | `clankoth.admin` | Xoá vùng |
| `/koth holo <movehere\|reset> <vùng>` | `clankoth.admin` | Dời hologram xếp hạng tới chỗ mình đứng, hoặc trả về mặc định |
| `/koth infoholo <movehere\|reset> <vùng>` | `clankoth.admin` | Tương tự cho hologram thông tin |
| `/koth set <vùng> radius\|duration <số\|default>` | `clankoth.admin` | Bán kính hiện scoreboard, thời lượng trận |
| `/koth reload` | `clankoth.admin` | Đọc lại config |

`clankoth.bypass.clanlock`: không bị khoá clan trong lúc có KOTH.

## Placeholder

`%clankoth_<key>%` lấy vùng mà người xem đang ở gần (trong bán kính arena). `%clankoth_z:<vùng>:<key>%` lấy theo một
vùng cụ thể. `%clankoth_near%` trả `true` khi đang ở gần một vùng.

| Key | Giá trị |
|---|---|
| `zone`, `active` | Tên vùng, có trận đang diễn ra không |
| `controller`, `controller_clan` | Người và bang đang giữ vùng |
| `time_left` | Thời gian còn lại của trận |
| `my_clan_time`, `my_clan_rank` | Thời gian chiếm và hạng của bang người xem |
| `top_<1-5>_clan`, `top_<1-5>_time` | Bảng xếp hạng |
| `next`, `next_in` | Trận kế tiếp và còn bao lâu |

## Build

```
./gradlew build
```

Ra file `build/libs/ClanKoth-<version>.jar`. Repo chỉ chứa mã nguồn, không có jar nào ngoài
`gradle/wrapper/gradle-wrapper.jar` của Gradle. Trước khi build, đặt vào thư mục `libs/`:

- `boxclans-api-1.0.0.jar`: build từ [BoxClans](https://github.com/FLD-TN/BoxClans) (`boxclans-api/build/libs/`)
