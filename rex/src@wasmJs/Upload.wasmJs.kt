@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.kotlintest.rex

import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/** A `File` the browser handed over, or any other `Blob`. Its bytes stay the browser's until they are sent. */
actual class UploadContent(internal val blob: JsAny)

private fun blobName(blob: JsAny): String? = js("(typeof blob.name === 'string' && blob.name) ? blob.name : null")
private fun blobSize(blob: JsAny): Double = js("blob.size")

/** A file a browser handed over in a drop or a paste, under its own name unless [name] gives another. */
fun uploadFile(blob: JsAny, name: String? = null): UploadFile =
    UploadFile(name ?: blobName(blob) ?: "file", blobSize(blob).toLong(), UploadContent(blob))

/**
 * The whole exchange, in the browser's own terms, so that a file's bytes go from the browser
 * to its socket without passing through the app, and no faster than the socket takes them:
 * a browser's socket accepts everything it is given at once and keeps what it hasn't sent.
 *
 * Answers `{ done, cancel }`. `done` settles with null once the server has closed the
 * connection with status 1000, and otherwise with why not.
 */
private fun startUpload(url: String, protocol: String, ticket: String, blob: JsAny, name: String, size: Double, chunk: Int, onSent: (Double) -> Unit): JsAny = js(
    """(() => {
        let socket = null;
        let settle;
        let finished = false;
        const timers = [];
        const done = new Promise((resolve) => { settle = resolve; });
        const finish = (why) => {
            if (finished) return;
            finished = true;
            timers.forEach(clearTimeout);
            // Dropped, whatever state it is in: a file that is short types nothing.
            try { if (socket && socket.readyState <= 1) socket.close(); } catch (error) {}
            settle(why);
        };
        let raw;
        try { raw = atob(ticket.replace(/-/g, '+').replace(/_/g, '/')); } catch (error) { raw = ''; }
        if (raw.length !== 16) {
            finish('Rex issued a ticket of ' + raw.length + ' bytes, where it is always 16');
            return { done, cancel() {} };
        }
        const hello = new Uint8Array(17);
        hello[0] = 0x01;
        for (let i = 0; i < 16; i++) hello[i + 1] = raw.charCodeAt(i);

        let welcomed = false;
        let complete = false;
        const pump = async () => {
            try {
                let at = 0;
                while (at < size) {
                    const piece = await blob.slice(at, Math.min(size, at + chunk)).arrayBuffer();
                    if (finished) return;
                    if (piece.byteLength === 0) break;
                    socket.send(piece);
                    at += piece.byteLength;
                    // Half a megabyte waiting is plenty; more would only fill memory.
                    while (socket.bufferedAmount > 8 * chunk && !finished) await new Promise((wake) => setTimeout(wake, 5));
                    if (finished) return;
                    onSent(Math.max(0, at - socket.bufferedAmount));
                }
                // The size was promised when the file was reserved, and the server holds to it.
                if (at !== size || blob.size !== size) return finish(name + ' changed while it was uploading');
                socket.send(new Uint8Array(0));
                complete = true;
                onSent(size);
                timers.push(setTimeout(() => finish('Rex didn\'t finish the upload'), 120000));
            } catch (error) {
                // A folder comes to a page looking like a file, and fails here when it is read.
                finish(name + ' can\'t be read' + (error && error.name ? ' (' + error.name + ')' : ''));
            }
        };
        try {
            socket = new WebSocket(url, protocol);
        } catch (error) {
            finish('The upload connection couldn\'t be opened');
            return { done, cancel() {} };
        }
        socket.binaryType = 'arraybuffer';
        timers.push(setTimeout(() => { if (!welcomed) finish('Rex didn\'t answer the upload\'s connection'); }, 10000));
        socket.onopen = () => socket.send(hello);
        socket.onmessage = (event) => {
            if (welcomed || finished || typeof event.data === 'string') return;
            const answer = new Uint8Array(event.data);
            if (answer[0] === 0x82) {
                const reason = answer[1];
                finish(reason === 1 ? 'Rex no longer knows this upload: its ticket expired or its session is gone'
                    : reason === 3 ? 'The terminal has closed'
                    : 'Rex refused the upload\'s connection (reason ' + reason + ')');
            } else if (answer[0] !== 0x81) {
                finish('Rex answered the upload\'s connection with something unexpected');
            } else {
                welcomed = true;
                pump();
            }
        };
        socket.onclose = (event) => {
            if (event.code === 1000 && complete) finish(null);
            else if (!welcomed && event.code === 1006) finish('The upload connection couldn\'t be opened');
            else if (event.code === 1006 || event.code === 1005) finish('The upload connection ended early');
            else if (event.reason) finish('Rex refused the upload: ' + event.reason + ' (' + event.code + ')');
            else finish('Rex refused the upload (' + event.code + ')');
        };
        return { done, cancel() { finish('cancelled'); } };
    })()""",
)

private fun doneOf(upload: JsAny): Promise<JsAny?> = js("upload.done")
private fun cancel(upload: JsAny): Unit = js("upload.cancel()")

internal actual suspend fun HttpClient.sendUpload(endpoint: RexEndpoint, ticket: String, file: UploadFile, onSent: (Long) -> Unit) {
    val upload = startUpload(relayUrl(endpoint, "/api/data"), UPLOAD_SUBPROTOCOL, ticket, file.content.blob, file.name, file.size.toDouble(), UPLOAD_CHUNK_BYTES) { onSent(it.toLong()) }
    val failure = try {
        (doneOf(upload).await<JsAny?>() as? JsString)?.toString()
    } finally {
        cancel(upload) // nothing to do once it is done; drops the connection if this was cancelled
    }
    if (failure != null) throw UploadFailed(failure, file.name)
}
