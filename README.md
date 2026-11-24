# L2 Boss Tracker

Android-приложение для Asterios x5: следит за убийствами саб РБ (Cabrio, Hallate, Kernon, Golkonda),
показывает окна респа и играет мелодию при убийстве боссов, у которых включён тумблер.

**Скачать последнюю версию:** https://github.com/OlehBohatyr/l2boss-tracker/releases/latest/download/l2boss-tracker.apk

- Данные: API l2lb.monster (время убийства по МСК).
- Фоновая служба опрашивает API раз в минуту, даже когда приложение закрыто.
- APK собирается автоматически GitHub Actions при каждом пуше в `main`.
