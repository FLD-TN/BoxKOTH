# BoxKOTH

Clan King-of-the-Hill events

## Build

```
./gradlew build
```

Repo chỉ có mã nguồn, không chứa file jar nào (trừ `gradle/wrapper/gradle-wrapper.jar` chuẩn của Gradle).
Trước khi build, tự đặt các jar sau vào thư mục `libs/` ở gốc repo:

- `boxclans-api-1.0.0.jar` - build từ [BoxClans](https://github.com/FLD-TN/BoxClans) (`boxclans-api/build/libs/`)
