#!/usr/bin/env python3
"""Execute unchanged production method bodies with deterministic JVM test doubles.
Not an Android instrumentation/UI rendering test. Requires only Python and a JDK.
"""
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/zlib/clone'

def methods(filename, names):
    text = (JAVA / 'ui' / filename).read_text(encoding='utf-8')
    parts = []
    for name in names:
        match = re.search(r'^    (?:private|public|protected) [^\n]*\b' + re.escape(name)
                          + r'\([^\n]*\) \{\n.*?^    \}', text, re.M | re.S)
        if not match:
            raise AssertionError('Method not found: ' + name)
        parts.append(match.group())
    return '\n'.join(parts)

books = methods('MyBooksView.java', ['retryLoadMore', 'loadPage', 'showPageFailure', 'syncState'])
categories = methods('CategoryView.java', ['loadTree', 'retryWithFailover', 'showErrorState'])
details = methods('BookDetailActivity.java', ['loadBook', 'showDetailError', 'updateDetailActions',
    'setDownloadBusy', 'requestFormats', 'confirmDefaultDownload', 'startDownload'])
resources = {}
for kind, key in re.findall(r'R\.(\w+)\.(\w+)', books + categories + details):
    resources.setdefault(kind, set()).add(key)
rjava = 'static class R {' + ''.join('static class ' + kind + ' {' + ''.join(
    'static final int ' + name + '=' + str(index + 1) + ';'
    for index, name in enumerate(sorted(names))) + '}' for kind, names in resources.items()) + '}'

# XML lint checks alongside runtime state-machine checks.
android = '{http://schemas.android.com/apk/res/android}'
xml_count = 0
for path in (ROOT / 'app/src/main/res').rglob('*.xml'):
    ET.parse(path)
    xml_count += 1
for filename, expected in [('activity_book_detail.xml', ['ll_detail_error', 'btn_detail_retry',
        'tv_detail_read_reason']), ('view_mybooks.xml', ['btn_mybooks_load_more_retry'])]:
    tree = ET.parse(ROOT / 'app/src/main/res/layout' / filename)
    ids = [node.get(android + 'id', '').split('/')[-1] for node in tree.iter()]
    for name in expected:
        assert ids.count(name) == 1, (filename, name)

prefix = r'''
import java.util.*;
import com.zlib.clone.model.*;
public class Batch1Regression {
static int assertions;
static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    assertions++;
}
static class Context {
    String getString(int id) { return "resource-" + id; }
    String getString(int id, Object... args) { return getString(id); }
    Context getResources() { return this; }
    int getColor(int id) { return id; }
}
static class Activity extends Context {
    boolean destroyed, finishing;
    boolean isDestroyed() { return destroyed; }
    boolean isFinishing() { return finishing; }
}
static class View {
    boolean enabled = true, visible;
    int background, color;
    Object text;
    void setEnabled(boolean value) { enabled = value; }
    void setText(Object value) { text = value; }
    void setBackgroundResource(int value) { background = value; }
    void setTextColor(int value) { color = value; }
}
static class ApiException extends Exception { final int code; ApiException(int c) { code=c; } }
static class Ui {
    static void display(View v, boolean shown) { if (v!=null) v.visible=shown; }
    static void toast(Context c, Object text) {}
    static String errorText(Exception e) { return e.toString(); }
    static boolean handleAuthError(Activity a, Exception e) {
        if (e instanceof ApiException && (((ApiException)e).code==401 || ((ApiException)e).code==403)) {
            a.finishing=true; return true;
        }
        return false;
    }
}
static class Task {
    interface Job<T> { T run() throws Exception; }
    interface Ok<T> { void ok(T value); }
    interface Err { void err(Exception error); }
    static class Pending<T> {
        Job<T> job; Ok<T> ok; Err err;
        Pending(Job<T> j, Ok<T> o, Err e) {job=j;ok=o;err=e;}
        void execute() throws Exception { ok.ok(job.run()); }
        void fail(Exception e) { err.err(e); }
        @SuppressWarnings("unchecked") void result(Object value) { ok.ok((T)value); }
    }
    static List<Pending<?>> queue = new ArrayList<>();
    static <T> void run(Job<T> job, Ok<T> ok, Err err) { queue.add(new Pending<T>(job,ok,err)); }
    static Pending<?> take() { return queue.remove(0); }
}
static class Api {
    static final Api INSTANCE = new Api();
    static Api get() { return INSTANCE; }
    int requestedPage, requestedId, downloads, setBaseCalls;
    String requestedHash, currentBase="old";
    Page<Book> saved(int p) { requestedPage=p; return page(p,2); }
    Page<Book> downloaded(int p) { return saved(p); }
    Book book(int id, String hash) { return new Book(); }
    List<Format> formats(int id, String hash) { return new ArrayList<>(); }
    DownloadInfo downloadInfo(int id, String hash) {
        requestedId=id;requestedHash=hash;downloads++;
        DownloadInfo info=new DownloadInfo();info.allowDownload=true;info.url="https://example.invalid/file";
        return info;
    }
    String base() { return currentBase; }
    void setBase(String base) { currentBase=base; setBaseCalls++; }
}
static class Hosts {
    static String pickNext(String base) { return "next"; }
    static String hostOf(String base) { return base; }
}
static class WebCategorySource {
    static class Tree { String baseUrl="tree"; List<String> roots=new ArrayList<>(); }
    static Tree fetchTree() { Tree t=new Tree(); t.roots.add("root"); return t; }
}
static class Adapter {
    List<Book> books = new ArrayList<>();
    boolean isEmpty() { return books.isEmpty(); }
    void clear() { books.clear(); }
    void addItems(List<Book> items) { books.addAll(items); }
}
static Page<Book> page(int current, int total) {
    Page<Book> p=new Page<Book>();p.current=current;p.totalPages=total;p.items.add(new Book());return p;
}
interface DialogInterface {
    interface OnClickListener { void onClick(DialogInterface dialog, int which); }
    interface OnCancelListener { void onCancel(DialogInterface dialog); }
}
static class AlertDialog implements DialogInterface {
    DialogInterface.OnClickListener positive, negative;
    DialogInterface.OnCancelListener cancel;
    boolean shown;
    void show() { shown=true; }
    void setOnCancelListener(DialogInterface.OnCancelListener c) { cancel=c; }
    static class Builder {
        AlertDialog dialog=new AlertDialog();
        Builder(Context c) {}
        Builder setTitle(int id) { return this; }
        Builder setMessage(int id) { return this; }
        Builder setPositiveButton(int id, DialogInterface.OnClickListener l) { dialog.positive=l;return this; }
        Builder setNegativeButton(int id, DialogInterface.OnClickListener l) { dialog.negative=l;return this; }
        AlertDialog create() { return dialog; }
    }
}
static class MyBooksView {
    static final String MODE_DOWNLOADED="downloaded";
    String mode="saved";
    Activity host=new Activity();
    Adapter adapter=new Adapter();
    int page, requestToken;
    boolean loading, hasMore=true, loadFailed, loadMoreFailed, loadedOnce=true;
    View errorDesc=new View(), skeletonView=new View(), emptyView=new View(), errorView=new View(),
        loadingMore=new View(), loadMoreRetry=new View();
    Activity activity() {return host;}
    Context getContext() {return host;}
    void updateEmptyTexts() {}
'''
middle = r'''
}
static class CategoryView {
    static final int LEVEL_ROOT=0, LEVEL_CHILDREN=1, LEVEL_BOOKS=2;
    int level,requestToken,renderCount,retries;
    boolean loading,lastFailureServerSide;
    String baseUrl;
    List<String> roots=new ArrayList<>();
    Activity host=new Activity();
    View categoryList=new View(),bookGrid=new View(),skeleton=new View(),emptyState=new View(),
        errorTitle=new View(),errorDesc=new View(),errorRetry=new View(),errorState=new View(),loadingMore=new View();
    Activity hostActivity() {return host;}
    Context getContext() {return host;}
    Context getResources() {return host;}
    void showSkeletonStateForLevel() {skeleton.visible=true;errorState.visible=false;}
    void renderRoot(boolean restore) {renderCount++;requestToken++;skeleton.visible=false;}
    boolean isServerSideFailure(Exception e) {return true;}
    String categoryErrorMessage(Exception e) {return "error";}
    void retryCurrent() {retries++;}
'''
last = r'''
}
static class BookDetailActivity extends Activity {
    int bookId=1,detailRequestToken,formatDialogs,enqueued;
    String bookHash="original";
    Book book;
    List<Format> formats;
    boolean detailLoading,downloadBusy;
    AlertDialog downloadDialog;
    View detailError=new View(),detailErrorText=new View(),detailRetry=new View(),progressBar=new View(),
        saveButton=new View(),downloadButton=new View(),readButton=new View(),readReason=new View();
    void bind(Book value) {book=value;updateDetailActions();}
    void showFormatDialog() {formatDialogs++;}
    void enqueueDownload(DownloadInfo info) {enqueued++;}
'''
tests = r'''
}
public static void main(String[] args) throws Exception {
    Exception network=new Exception("offline");
    // Real loadTree and error UI method bodies: L0/L1 failure, retry, stale token, dead host.
    for(int level=0;level<=1;level++) {
        CategoryView c=new CategoryView();c.level=level;c.loadTree();
        check(c.loading && c.skeleton.visible,"tree loading");
        Task.take().fail(network);
        check(!c.loading && !c.skeleton.visible && c.errorState.visible && c.errorRetry.enabled,"tree failure exits skeleton");
        c.loadTree();Task.take().execute();check(c.renderCount==1 && !c.loading,"tree retry success");
    }
    CategoryView c=new CategoryView();c.loadTree();Task.Pending<?> old=Task.take();c.requestToken++;old.fail(network);
    check(!c.errorState.visible,"stale tree failure ignored");
    c=new CategoryView();c.loadTree();old=Task.take();c.level=2;old.fail(network);
    check(!c.errorState.visible,"tree response rejected at books level");
    c=new CategoryView();c.lastFailureServerSide=true;c.retryWithFailover();
    check(c.loading && !c.errorRetry.enabled,"failover locks retry");
    old=Task.take();int changes=Api.get().setBaseCalls;c.requestToken++;old.execute();
    check(c.retries==0 && Api.get().setBaseCalls==changes,"stale failover cannot change global server");
    c=new CategoryView();c.lastFailureServerSide=true;c.retryWithFailover();Task.take().fail(network);
    check(!c.loading && c.errorRetry.enabled && c.retries==1,"failed failover still retries current server");
    // Real pagination request/callback/UI/retry bodies.
    MyBooksView m=new MyBooksView();m.loadPage(1,true);Task.take().result(page(1,3));
    check(m.page==1 && m.adapter.books.size()==1 && m.hasMore,"first page success");
    m.loadPage(2,false);Task.take().fail(network);
    check(m.page==1 && m.hasMore && m.loadMoreFailed && m.loadMoreRetry.visible,"pagination failure preserves state");
    m.loadPage(2,false);check(Task.queue.isEmpty(),"automatic paging blocked after failure");
    m.retryLoadMore();check(m.loading && !m.loadMoreRetry.visible && Task.queue.size()==1,"manual retry only once");
    m.retryLoadMore();check(Task.queue.size()==1,"duplicate retry blocked");
    Task.take().fail(network);check(m.loadMoreRetry.visible && m.page==1,"second failure remains retryable");
    m.retryLoadMore();Task.take().execute();
    check(Api.get().requestedPage==2 && m.page==2 && m.adapter.books.size()==2 && !m.hasMore,"retry same page then true end");
    m.loadPage(3,false);check(Task.queue.isEmpty(),"true last page stops loading");
    m=new MyBooksView();m.loadPage(1,true);old=Task.take();m.mode="downloaded";m.loadPage(1,true);
    old.result(page(1,3));check(m.loading && m.adapter.isEmpty(),"stale success does not touch new mode");
    Task.take().fail(network);check(m.errorView.visible && !m.skeletonView.visible,"first-page failure has error UI");
    m=new MyBooksView();m.loadPage(1,true);Task.take().result(null);
    check(m.errorView.visible && m.hasMore,"null page is error not end");
    m=new MyBooksView();m.loadPage(1,true);Task.take().result(page(1,3));m.loadPage(2,false);Task.take().result(null);
    check(m.page==1 && m.loadMoreRetry.visible,"null next page preserves retry");
    m=new MyBooksView();m.loadPage(1,true);old=Task.take();m.host.destroyed=true;old.result(page(1,3));
    check(m.adapter.isEmpty(),"destroyed host not updated");
    // Real detail loading/actions/format/download callbacks, controlled async responses.
    BookDetailActivity d=new BookDetailActivity();d.loadBook();d.loadBook();
    check(Task.queue.size()==1 && !d.readButton.enabled,"detail duplicate request blocked");
    Task.take().fail(network);check(d.detailError.visible && d.detailRetry.enabled && !d.detailLoading,"detail retry shown");
    d.loadBook();Book b=new Book();b.id=1;b.hash="original";Task.take().result(b);
    check(!d.detailError.visible && !d.readButton.enabled && d.readReason.visible,"unreadable reason visible");
    b.readOnlineAvailable=true;b.readOnlineUrl=" ";d.updateDetailActions();check(!d.readButton.enabled,"blank reader URL disabled");
    b.readOnlineUrl="https://example.invalid/read";d.updateDetailActions();check(d.readButton.enabled && !d.readReason.visible,"readable book enabled");
    d=new BookDetailActivity();d.loadBook();Task.take().result(null);check(d.detailError.visible,"null detail error");
    d=new BookDetailActivity();d.loadBook();old=Task.take();d.detailRequestToken++;old.result(b);
    check(d.book==null,"stale detail response ignored");
    d=new BookDetailActivity();d.book=b;d.requestFormats();Task.take().fail(network);
    check(d.downloadDialog.shown && d.downloadBusy && Task.queue.isEmpty(),"format failure waits for confirmation");
    d.downloadDialog.negative.onClick(d.downloadDialog,0);check(!d.downloadBusy && Task.queue.isEmpty(),"cancel never downloads");
    d.requestFormats();Task.take().result(new ArrayList<Format>());
    d.downloadDialog.cancel.onCancel(d.downloadDialog);check(!d.downloadBusy,"back/outside cancel unlocks");
    d.requestFormats();Task.take().result(new ArrayList<Format>());
    d.downloadDialog.positive.onClick(d.downloadDialog,0);Task.take().execute();
    check(d.enqueued==1 && !d.downloadBusy && Api.get().requestedId==1,"confirmed default download");
    Format f=new Format();f.id=77;f.hash="format77";d.startDownload(f);Task.take().execute();
    check(Api.get().requestedId==77 && "format77".equals(Api.get().requestedHash),"selected id/hash kept together");
    d.startDownload(f);Task.take().fail(network);check(!d.downloadBusy && Task.queue.isEmpty(),"selected failure never silently falls back");
    for(int code:new int[]{401,403}) {
        d=new BookDetailActivity();d.loadBook();Task.take().fail(new ApiException(code));check(d.finishing && !d.detailError.visible,"detail auth redirect");
        d=new BookDetailActivity();d.book=b;d.requestFormats();Task.take().fail(new ApiException(code));
        check(d.finishing && d.downloadDialog==null && Task.queue.isEmpty(),"formats auth must not default download");
        d=new BookDetailActivity();d.book=b;d.startDownload(f);Task.take().fail(new ApiException(code));
        check(d.finishing && !d.downloadBusy && d.enqueued==0,"download auth must not enqueue");
    }
    check(Task.queue.isEmpty(),"no unexpected pending requests");
    System.out.println("PASS: " + assertions + " deterministic state-machine assertions");
}
}
'''
with tempfile.TemporaryDirectory(prefix='zlib-batch1-') as directory:
    path=Path(directory)
    code=prefix.replace('public class Batch1Regression {', 'public class Batch1Regression {\n'+rjava)+books+middle+categories+last+details+tests
    (path/'Batch1Regression.java').write_text(code,encoding='utf-8')
    models=[JAVA/'model'/name for name in ['Book.java','Page.java','Format.java','DownloadInfo.java']]
    subprocess.run(['javac','-encoding','UTF-8','-d',str(path),str(path/'Batch1Regression.java'),*map(str,models)],check=True)
    subprocess.run(['java','-cp',str(path),'Batch1Regression'],check=True)
print(f'PASS: {xml_count} resource XML files parsed; new view IDs unique')
print('LIMIT: deterministic JVM doubles; not device/network/visual testing')
