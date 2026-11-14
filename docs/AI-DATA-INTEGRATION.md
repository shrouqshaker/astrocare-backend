# ربط AI & Data بالباك اند

**التاريخ:** 2026-11-14
**النسخة:** 1.0.0
**المستودع:** `astrocare-backend`
**حزمة المرجع:** `AstroCare_AI_Data_FINAL_v1.0.0.zip`

---

## 1. الخلاصة

الباك اند وحزمة AI/Data يعملان على **نفس `contracts/` بايتًا بايتًا** (تم التحقق بـ `npm run contracts:verify`). لا حاجة لتعديل العقد أو نسخ الحقول.

التقسيم المعتمد:

| المكوّن | المسؤولية |
|---|---|
| Android / Kotlin | التقييم المحلي للقواعد، الفرز، الرؤى، العرض العربي، العمل offline |
| `POST /sync/batch` | استقبال القياسات والأعراض والإنذارات وحفظها idempotently |
| MongoDB | تخزين السجلات الخام والإنذارات المرفوعة؛ لا يخزّن إنذارات `overview` المشتقة |
| `GET /overview` | إعادة حساب الحالة الحالية والإنذارات من أحدث السجلات |
| `GET /trends` | بناء `TrendReport` سبعة أيام + الرؤى |
| AI/Data Python/Kotlin | المرجع الحتمي للقواعد والمعرّفات والفيكتورات |

**قرار مهم:** الباك اند لا يستدعي Python في الإنتاج. بدلًا من ذلك، أعيدت كتابة نفس الخوارزميات في Node وربطت باختبار parity يطابق مخرج Python حرفيًا. هذا يبقي Railway بسيطًا (Node + MongoDB) ويمنع drift بين اللغتين.

---

## 2. مسار البيانات

```text
┌──────────────── Android (Kotlin) ────────────────┐
│ RuleEngine / Triage / Insights / Arabic UI       │
│ UUIDv5(alert|astronaut|rule|revision|ids)        │
└───────────────┬─────────────────────────────────┘
                │ POST /api/v1/sync/batch
                │ X-API-Key + batchId + same UUIDs
                ▼
┌──────────────── Node / Express / MongoDB ────────┐
│ sync: insertOnce(astronautId,id) + audit         │
│ history: measurements / checkins / alerts        │
│ overview: latest per metric -> rule engine       │
│ trends: 7-day report + baseline + insights       │
└───────────────┬─────────────────────────────────┘
                │ GET overview / trends / history
                ▼
┌──────────────── Android (Kotlin) ────────────────┐
│ اعرض الحالة، الإنذارات، الرؤى، والأدلة           │
│ قارن IDs مع المرجع المحلي قبل الدمج              │
└─────────────────────────────────────────────────┘
```

### قواعد التزامن

1. أنشئ `id` مرة واحدة على Android لكل قياس/check-in/إنذار، واستخدم نفس `id` في كل retry.
2. الباك اند يضمن `(astronautId, id)` فريدًا؛ التكرار يعيد `duplicate` ولا يغيّر المحتوى.
3. الإنذارات التي يولّدها Android تُرفع مع `triggeredBy` و`evidence` كما هي.
4. `overview` يعيد حساب الإنذارات ولا يحفظها؛ لا تُنشئ سجلات مكررة.
5. لا تخلط astronautين في batch واحد؛ كل item يجب أن يطابق `batch.astronautId`.

---

## 3. المعرّفات الحتمية (الإصلاح الأهم)

### المشكلة السابقة

الباك اند كان يستخدم:

```js
createHash('sha256') ... bytes[6] = (bytes[6] & 15) | 80
```

الرقم `80` هنا **عشري** = `0x50`، لذلك كان الإصدار يظهر `5` رغم أن الخوارزمية SHA-256 وليست UUIDv5. النتيجة: نفس الإنذار يأخذ ID مختلفًا في Python/Kotlin والباك اند، فيُنشئ سجلين عند الـ upsert.

### الخوارزمية الموحدة

```text
namespace = 6f5d1a0e-6f2c-4f4b-9a0f-1d7c2b3e4a55
alert     = UUIDv5(namespace, "alert|{astronautId}|{ruleId}|{revision}|{measurementIds,checkinIds}")
insight   = UUIDv5(namespace, "insight|{astronautId}|{ruleId}|{evidence[0].observedAt}")
```

- IDs داخل `triggeredBy` مرتبة قبل بناء البذرة.
- UUIDv5 = SHA-1 + namespace، وليس SHA-256.
- تم التحقق من `stableUuid('test') = 0565a3b2-100b-5ef6-ba25-fd0842c4fb01` في Node وPython.
- نفس السيناريو الحرج ينتج نفس الإنذارات الخمسة والرؤى الأربع بنفس IDs في الطرفين.

---

## 4. إصلاحات المطابقة الأخرى

| المشكلة | قبل | بعد |
|---|---|---|
| مقارنة الرؤية السلبية | `<=` | `<` مثل Python |
| التقريب | `Math.round` قد يعطي 36.67 | `ROUND_HALF_UP` من أقصر تمثيل عشري، 36.68 |
| `isSynthetic` | `every` (كل السجلات) | `some` (أي دليل اصطناعي) مثل Python |
| ترتيب IDs | ترتيب ظهور evidence | `sort()` قبل البذرة |
| الحقول الاختيارية | `null` صريح | تُحذف إن كانت `null` مثل Python |
| بناء السياق | مكرر داخل overview | `src/services/context.service.js` مشترك مع الاختبار |
| تنسيق الوقت | `.000Z` | `Z` عند عدم وجود كسور، مثل Python |

> `generatedBy` يبقى `backend_optional` لأن الباك اند هو من يبني التقرير، وليس Python. هذا موثق في `docs/BACKEND-DECISIONS.md`.

---

## 5. اختبار Parity الدائم

الملف `test/fixtures/reference-parity.v1.json` يحتوي مدخلات سيناريو ألم الصدر الحرج + مخرج Python المرجعي الكامل (5 إنذارات، 4 رؤى، metrics، window).

```powershell
npm run test:reference
```

الاختبار يتحقق من:

- UUIDv5 المعروف.
- `overallStatus = critical`.
- تطابق `alerts` حرفيًا، بما فيها IDs و`evidence` و`triggeredBy`.
- تطابق `report.metrics` و`report.insights` و`window` حرفيًا.
- صلاحية `alert` و`trend-report` مقابل المخططات.

---

## 6. واجهات API المطلوبة من Android

كل المسارات تحت `/api/v1` وتحتاج `X-API-Key` عدا `/health`.

| الاستخدام | الطلب |
|---|---|
| فحص الجاهزية | `GET /health` |
| رفع دفعة | `POST /sync/batch` |
| سجل القياسات | `GET /astronauts/demo-astronaut-01/measurements?metric=heart_rate&limit=200` |
| سجل الأعراض | `GET /astronauts/demo-astronaut-01/symptom-checkins` |
| سجل الإنذارات | `GET /astronauts/demo-astronaut-01/alerts?level=critical` |
| الحالة الحالية | `GET /astronauts/demo-astronaut-01/overview` |
| الاتجاهات | `GET /astronauts/demo-astronaut-01/trends?utcOffsetMinutes=0` |
| القواعد/الأدلة الإنجليزية | `GET /rules/latest`, `GET /guides/latest` |

مثال دفع:

```bash
curl -X POST "$BASE/sync/batch" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  --data @example-sync.json
```

---

## 7. التشغيل والتحقق

```powershell
npm.cmd ci
Copy-Item .env.example .env
# عدّل MONGODB_URI و API_KEY
npm.cmd start
```

```powershell
npm.cmd run contracts:verify
npm.cmd run test:unit
npm.cmd run test:reference
npm.cmd test
```

النتيجة المتوقعة بعد هذه الجولة: **64 اختبارًا** (49 unit + 13 integration + 2 parity)، مع بقاء 34/34 و5/5 فيكتورات.

---

## 8. النشر على Railway

1. ادمج تعديلات `src/services/rules.service.js` و`trends.service.js` و`context.service.js` و`overview.service.js` واختبار parity.
2. أعد النشر؛ لا تحتاج migration لأن `overview/trends` يحسبان on-demand.
3. **مهم:** إذا كان Android يخزّن IDs قديمة من `overview` قبل النشر، أعد جلب `overview` بعد النشر؛ IDs ستتغير مرة واحدة لتطابق UUIDv5.
4. تحقق:

```bash
curl "$BASE/health"
curl -H "X-API-Key: $API_KEY" "$BASE/astronauts/demo-astronaut-01/overview"
```

---

## 9. حدود يجب توثيقها

- `overview` امتداد غير موجود في العقد الأصلي؛ اعتبره اقتراحًا وليس جزءًا من `contracts/`.
- `API_KEY` في APK ليس مصادقة مستخدم حقيقية؛ بيانات الديمو اصطناعية فقط.
- `generatedBy` في `/trends` هو `backend_optional` وليس `python_reference`.
- الباك اند يخدم `rules`/`guides` الإنجليزية فقط؛ المحتوى العربي والصور تبقى في حزمة Android `ai-data` حتى يُضاف endpoint عربي لاحقًا.
- الحدود الطبية كلها تجريبية وليست بروتوكول طيران معتمد.
