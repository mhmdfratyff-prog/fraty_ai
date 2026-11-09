# نشر خادم FRATY AI على Render

## المسار المقترح

أعددت ملف `render.yaml` في جذر المشروع لتجربة خدمة Node.js على Render. Render يقبل مستودع GitHub أو GitLab أو Bitbucket، ويمنح خدمة الويب عنوانًا عامًا على `onrender.com` مع TLS. ملف Blueprint يضبط منطقة Frankfurt، أمر تشغيل الخادم، وفحص الصحة `/health`. [1] [6]

1. ارفع مجلد المشروع إلى مستودع Git خاص؛ لا ترفع ملف `.env` أو أي مفاتيح.
2. في Render اختر إنشاء Blueprint، ثم اربط المستودع الذي يحتوي `render.yaml` في الجذر.
3. أدخل `OPENAI_API_KEY` و`OPENAI_MODEL` عند طلبهما. Render ينشئ `APP_ACCESS_TOKEN` عشوائيًا من دون وضعه في المستودع.
4. بعد نجاح النشر، افتح عنوان الخدمة العام واختبر `https://<اسم-الخدمة>.onrender.com/health`؛ المتوقع JSON فيه `ok: true`.
5. في APK افتح الإعدادات مرة واحدة، وضع عنوان الخدمة الأساسي مثل `https://fraty-ai-backend.onrender.com` بلا `/api/v1`، ثم أدخل قيمة `APP_ACCESS_TOKEN` التي أنشأها Render واحفظها. بعد الحفظ يستخدم التطبيق الخادم بدل رسالة «الخادم غير مضبوط».

يستطيع الخادم اكتشاف `RENDER_EXTERNAL_URL` تلقائيًا، لذلك لا يلزم ضبط `PUBLIC_BASE_URL` في Render. يبقى مفتاح OpenAI ومفتاح الصور على الخادم فقط؛ لا تضعهما في إعدادات التطبيق أو APK. [2]

## الصور والتخزين

لتفعيل الصور بعد نشر الخادم، أضف `OPENAI_IMAGE_MODEL` من لوحة Render إلى اسم نموذج صور متاح لحساب المزوّد. يستخدم adapter المفتاح `OPENAI_API_KEY` ما لم تضبط `IMAGE_API_KEY` منفصلًا. يرسل الخادم رابط الصورة من نطاق الخدمة ويحفظها مؤقتًا.

إعداد Blueprint الحالي يستخدم الخطة المجانية للاختبار فقط. الخدمة المجانية تتوقف بعد 15 دقيقة بلا زيارات، وقد يستغرق إيقاظها نحو دقيقة. كما أن نظام الملفات فيها مؤقت، وتُفقد الصور عند إعادة التشغيل أو النشر أو الإيقاف؛ لا تعتمد عليها كاستضافة إنتاجية. [3]

للاستخدام المستمر، اختر خطة خدمة مدفوعة وأضف قرصًا دائمًا في Render عند `/var/data`، ثم اضبط متغير `MEDIA_DIR=/var/data/media`. الأقراص الدائمة متاحة للخدمات المدفوعة، وتُحاسب إضافةً إلى الحوسبة. تعرض صفحة الأسعار الحالية خطة صغيرة بحوالي 7 دولارات شهريًا للمعالجة، والتخزين الدائم بحوالي 0.25 دولار لكل GB شهريًا؛ قد تُضاف رسوم استخدام أو نقل بيانات، لذا راجع لوحة الفوترة قبل إنشاء الموارد. [4] [5]

## ما يلزم قبل النشر

لا يوجد خادم منشور حاليًا؛ ملف Blueprint والدليل يجهزان مسار النشر فقط. إنشاء الخدمة يحتاج حساب Render ومستودع Git ومفتاح مزوّد النموذج. لم أُنشئ خدمة أو موردًا مدفوعًا ولم أضع أسرارًا في المشروع.

مسار الفيديو ما زال يحتاج اختيار مزوّد فيديو وتنفيذ adapter له. حتى ذلك الحين يعرض التطبيق رسالة إعداد واضحة بدل ادعاء إنشاء فيديو. كما أن وضع البرمجة/التطبيق يرسل الطلب إلى نموذج المحادثة، لكنه لا يبني APK أو ينشر تطبيقًا تلقائيًا.

## المراجع

[1]: https://render.com/docs/web-services "Render Web Services documentation"
[2]: https://render.com/docs/environment-variables "Render default environment variables"
[3]: https://render.com/docs/free "Render free instance limitations"
[4]: https://render.com/docs/disks "Render persistent disk documentation"
[5]: https://render.com/pricing "Render current pricing"
[6]: https://render.com/docs/blueprint-spec "Render Blueprint YAML reference"
