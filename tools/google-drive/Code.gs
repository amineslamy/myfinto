/**
 * سرویس دریافت گزارش‌های برنامه «ثبت کارکرد» و ذخیره آن‌ها در گوگل درایو.
 *
 * مراحل استفاده:
 * 1) در سایت script.google.com یک پروژه جدید بسازید و این کد را داخل Code.gs بگذارید.
 * 2) مقدار SHARED_SECRET را به یک رمز دلخواه تغییر دهید (همین رمز را در تنظیمات برنامه وارد می‌کنید).
 * 3) از منوی Deploy > New deployment گزینه Web app را انتخاب کنید.
 *    - Execute as: Me
 *    - Who has access: Anyone
 * 4) آدرس Web app URL را کپی کرده و در تنظیمات برنامه (آیکن چرخ‌دنده) در فیلد «آدرس وب‌اپ اسکریپت گوگل» بگذارید.
 */

var SHARED_SECRET = 'change-me';
var FOLDER_NAME = 'FintoReports';

function doPost(e) {
  try {
    var params = (e && e.parameter) ? e.parameter : {};

    if (SHARED_SECRET && params.secret !== SHARED_SECRET) {
      return respond({ status: 'error', message: 'کلید امنیتی نادرست است' });
    }

    var fileName = params.fileName || ('report_' + new Date().getTime());
    var mimeType = params.mimeType || 'application/octet-stream';
    var description = params.description || '';
    var encoded = params.contentBase64 || '';

    if (!encoded) {
      return respond({ status: 'error', message: 'محتوای فایل ارسال نشده است' });
    }

    var bytes = Utilities.base64Decode(encoded);
    var folder = getOrCreateFolder();
    var file = folder.createFile(Utilities.newBlob(bytes, mimeType, fileName));
    if (description) {
      file.setDescription(description);
    }

    return respond({ status: 'ok', fileId: file.getId(), url: file.getUrl() });
  } catch (error) {
    return respond({ status: 'error', message: String(error) });
  }
}

function doGet() {
  return respond({ status: 'ok', message: 'Finto drive endpoint is up' });
}

function getOrCreateFolder() {
  var folders = DriveApp.getFoldersByName(FOLDER_NAME);
  if (folders.hasNext()) {
    return folders.next();
  }
  return DriveApp.createFolder(FOLDER_NAME);
}

function respond(payload) {
  return ContentService
    .createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}
