#!/usr/bin/env python3
"""Apply SecureMessenger Tor/API overlays onto synced Signal-Android sources."""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
VENDOR = ROOT / "vendor" / "signal"
OVERLAYS = VENDOR / "overlays"


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def copy_overlay(src_name: str, dest: Path) -> None:
    src = OVERLAYS / src_name
    if not src.exists():
        die(f"missing overlay {src}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    print(f"overlay → {dest.relative_to(ROOT)}")


def ensure_import(text: str, import_line: str, anchor: str) -> str:
    if import_line.strip() in text:
        return text
    if anchor not in text:
        die(f"import anchor not found: {anchor}")
    return text.replace(anchor, anchor + "\n" + import_line, 1)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new.strip() in text or "SignalSocksHolder.get()" in text and "socksProxy" in text and label.startswith("socks"):
        # Idempotent for socks blocks
        if old not in text:
            return text
    if old not in text:
        if "SignalSocksHolder.get()" in text and label.startswith("socks"):
            return text
        if "getAttachmentV4UploadForm" in text and label == "apis":
            return text
        die(f"patch block missing ({label})")
    return text.replace(old, new, 1)


def patch_push_socket() -> None:
    path = VENDOR / "libsignal-service/src/main/java/org/whispersystems/signalservice/internal/push/PushServiceSocket.java"
    text = path.read_text()
    # Upstream moved TlsProxySocketFactory into org.signal.network.util (was signalservice.api.util).
    tls_import = "import org.signal.network.util.TlsProxySocketFactory;"
    if "import org.whispersystems.signalservice.api.util.TlsProxySocketFactory;" in text:
        tls_import = "import org.whispersystems.signalservice.api.util.TlsProxySocketFactory;"
    text = ensure_import(
        text,
        "import org.signal.core.util.SignalSocksHolder;",
        tls_import,
    )
    text = replace_once(
        text,
        """      if (proxy.isPresent()) {
        builder.socketFactory(new TlsProxySocketFactory(proxy.get().getHost(), proxy.get().getPort(), dns));
      }""",
        """      java.net.Proxy socksProxy = SignalSocksHolder.get();
      if (socksProxy != null) {
        builder.proxy(socksProxy);
      } else if (proxy.isPresent()) {
        builder.socketFactory(new TlsProxySocketFactory(proxy.get().getHost(), proxy.get().getPort(), dns));
      }""",
        "socks-push",
    )
    if "getAttachmentV4UploadForm" not in text:
        text = replace_once(
            text,
            """  public ResumableUploadSpec getResumableUploadSpec(AttachmentUploadForm uploadForm) throws IOException {
    return new ResumableUploadSpec(Util.getSecretBytes(64),
                                   Util.getSecretBytes(16),
                                   uploadForm.key,
                                   uploadForm.cdn,
                                   getResumableUploadUrl(uploadForm),
                                   System.currentTimeMillis() + CDN2_RESUMABLE_LINK_LIFETIME_MILLIS,
                                   uploadForm.headers);
  }

  public AttachmentDigest uploadAttachment(PushAttachmentData attachment) throws IOException {""",
            """  public ResumableUploadSpec getResumableUploadSpec(AttachmentUploadForm uploadForm) throws IOException {
    return new ResumableUploadSpec(Util.getSecretBytes(64),
                                   Util.getSecretBytes(16),
                                   uploadForm.key,
                                   uploadForm.cdn,
                                   getResumableUploadUrl(uploadForm),
                                   System.currentTimeMillis() + CDN2_RESUMABLE_LINK_LIFETIME_MILLIS,
                                   uploadForm.headers);
  }

  /** SecureMessenger: restore CS API used by media upload. */
  public AttachmentUploadForm getAttachmentV4UploadForm() throws IOException {
    String responseText = makeServiceRequest("/v4/attachments/form/upload", "GET", (String) null);
    return JsonUtil.fromJson(responseText, AttachmentUploadForm.class);
  }

  /** SecureMessenger: sealed-sender delivery certificate. */
  public byte[] getSenderCertificate() throws IOException {
    String responseText = makeServiceRequest("/v1/certificate/delivery", "GET", (String) null);
    DeliveryCertificateResponse parsed = JsonUtil.fromJson(responseText, DeliveryCertificateResponse.class);
    if (parsed.getCertificate() == null || parsed.getCertificate().length == 0) {
      throw new IOException("Empty sender certificate");
    }
    return parsed.getCertificate();
  }

  public AttachmentDigest uploadAttachment(PushAttachmentData attachment) throws IOException {""",
            "apis",
        )
    path.write_text(text)
    print(f"patched {path.relative_to(ROOT)}")


def patch_websocket() -> None:
    path = VENDOR / "libsignal-service/src/main/java/org/whispersystems/signalservice/internal/websocket/OkHttpWebSocketConnection.java"
    text = path.read_text()
    tls_import = "import org.signal.network.util.TlsProxySocketFactory;"
    if "import org.whispersystems.signalservice.api.util.TlsProxySocketFactory;" in text:
        tls_import = "import org.whispersystems.signalservice.api.util.TlsProxySocketFactory;"
    text = ensure_import(
        text,
        "import org.signal.core.util.SignalSocksHolder;",
        tls_import,
    )
    text = replace_once(
        text,
        """      if (signalProxy.isPresent()) {
        clientBuilder.socketFactory(new TlsProxySocketFactory(signalProxy.get().getHost(), signalProxy.get().getPort(), dns));
      }""",
        """      java.net.Proxy socksProxy = SignalSocksHolder.get();
      if (socksProxy != null) {
        clientBuilder.proxy(socksProxy);
      } else if (signalProxy.isPresent()) {
        clientBuilder.socketFactory(new TlsProxySocketFactory(signalProxy.get().getHost(), signalProxy.get().getPort(), dns));
      }""",
        "socks-ws",
    )
    path.write_text(text)
    print(f"patched {path.relative_to(ROOT)}")


def patch_url_extensions() -> None:
    path = VENDOR / "libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalUrlExtensions.kt"
    text = path.read_text()
    tls_import = "import org.signal.network.util.TlsProxySocketFactory"
    if "import org.whispersystems.signalservice.api.util.TlsProxySocketFactory" in text:
        tls_import = "import org.whispersystems.signalservice.api.util.TlsProxySocketFactory"
    text = ensure_import(
        text,
        "import org.signal.core.util.SignalSocksHolder",
        tls_import,
    )
    text = replace_once(
        text,
        """  if (configuration.signalProxy.isPresent) {
    val proxy = configuration.signalProxy.get()
    builder.socketFactory(TlsProxySocketFactory(proxy.host, proxy.port, configuration.dns))
  }""",
        """  val socksProxy = SignalSocksHolder.get()
  if (socksProxy != null) {
    builder.proxy(socksProxy)
  } else if (configuration.signalProxy.isPresent) {
    val proxy = configuration.signalProxy.get()
    builder.socketFactory(TlsProxySocketFactory(proxy.host, proxy.port, configuration.dns))
  }""",
        "socks-url",
    )
    path.write_text(text)
    print(f"patched {path.relative_to(ROOT)}")


def patch_jackson_kotlin_module() -> None:
    """Jackson 2.19+ removed KotlinModule()'s no-arg ctor; use Builder."""
    path = VENDOR / "network-jvm/src/main/java/org/signal/network/util/JsonUtil.java"
    text = path.read_text()
    old = "objectMapper.registerModule(new KotlinModule());"
    new = "objectMapper.registerModule(new KotlinModule.Builder().build());"
    if new in text:
        print(f"already patched {path.relative_to(ROOT)}")
        return
    if old not in text:
        die("patch block missing (jackson-kotlin-module)")
    path.write_text(text.replace(old, new, 1))
    print(f"patched {path.relative_to(ROOT)}")


def patch_nowhere_buffered_sink_okio() -> None:
    """Okio 3.18+ added BufferedSink.utf8Appendable(); Signal stub lacks it."""
    path = (
        VENDOR
        / "libsignal-service/src/main/java/org/whispersystems/signalservice/internal/push/NowhereBufferedSink.java"
    )
    text = path.read_text()
    if "utf8Appendable()" in text:
        print(f"already patched {path.relative_to(ROOT)}")
        return
    needle = """  @Override
  public Buffer getBuffer() {
    return null;
  }
}
"""
    insert = """  @Override
  public Buffer getBuffer() {
    return null;
  }

  @Override
  public Appendable utf8Appendable() {
    return new Appendable() {
      @Override
      public Appendable append(CharSequence csq) {
        return this;
      }

      @Override
      public Appendable append(CharSequence csq, int start, int end) {
        return this;
      }

      @Override
      public Appendable append(char c) {
        return this;
      }
    };
  }
}
"""
    if needle not in text:
        die("patch block missing (nowhere-buffered-sink-okio)")
    if "import java.lang.Appendable;" not in text and "import java.lang.Appendable" not in text:
        # Appendable is java.lang; no import required, but keep file compiling as-is.
        pass
    path.write_text(text.replace(needle, insert, 1))
    print(f"patched {path.relative_to(ROOT)}")


def main() -> None:
    if not (VENDOR / "libsignal-service/src").is_dir():
        die("Signal vendor sources missing — run scripts/sync-signal-vendor.sh first")
    copy_overlay(
        "SignalSocksHolder.kt",
        VENDOR / "util-jvm/src/main/java/org/signal/core/util/SignalSocksHolder.kt",
    )
    copy_overlay(
        "DeliveryCertificateResponse.kt",
        VENDOR
        / "libsignal-service/src/main/java/org/whispersystems/signalservice/internal/push/DeliveryCertificateResponse.kt",
    )
    patch_push_socket()
    patch_websocket()
    patch_url_extensions()
    patch_jackson_kotlin_module()
    patch_nowhere_buffered_sink_okio()
    print("Signal overlays applied.")


if __name__ == "__main__":
    main()
