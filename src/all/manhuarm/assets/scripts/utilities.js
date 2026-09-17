const serializeHeaders = (h) => {
  if (!h) return "{}";
  if (h instanceof Headers) {
    const obj = {};
    h.forEach((v, k) => {
      obj[k] = v;
    });
    return JSON.stringify(obj);
  }
  return JSON.stringify(h);
};

const XHR = window.XMLHttpRequest;

function XHRProxy() {
  const xhr = new XHR();
  const state = { method: "", url: "", body: null, headers: {} };

  return new Proxy(xhr, {
    get(target, prop) {
      if (prop === "setRequestHeader") {
        return function (header, value) {
          state.headers[header] = value;
          return target.setRequestHeader(header, value);
        };
      }

      if (prop === "open") {
        return function (method, url) {
          state.method = method;
          state.url = url;
          return target.open(method, url);
        };
      }

      if (prop === "send") {
        return function (body) {
          state.body = body;
          fetch(state.url, {
            method: state.method,
            headers: state.headers,
            body: state.body,
          })
            .then((res) => res.text())
            .then((data) => {
              target.status = 200;
              target.readyState = 4;
              target.responseText = data;
              if (target.onload) target.onload();
              if (target.onreadystatechange) target.onreadystatechange();
            })
            .catch((err) => {
              target.readyState = 4;
              if (target.onerror) target.onerror(err);
            });
        };
      }

      const val = target[prop];
      return typeof val === "function" ? val.bind(target) : val;
    },
    set(target, prop, value) {
      target[prop] = value;
      return true;
    },
  });
}

XHRProxy.prototype = XHR.prototype;

// The site detects scraping environments by checking whether
// `Function.prototype.toString` still returns "[native code]" for
// XMLHttpRequest, setTimeout, setInterval and Worker before issuing the OCR
// request. Proxies keep the replaced implementations indistinguishable from
// native ones for that check, so the real OCR request is still captured.
window.XMLHttpRequest = new Proxy(XHRProxy, {});
Object.defineProperty(window.XMLHttpRequest, 'name', {
    value: "XMLHttpRequest"
});

const interval = window.setInterval;
window.setInterval = new Proxy(interval, {
  apply(target, thisArg, args) {
    const [callback, delay, ...rest] = args;
    return Reflect.apply(target, thisArg, [callback, (delay ?? 0) * 0.01, ...rest]);
  },
});
Object.defineProperty(window.setInterval, 'name', {
    value: "setInterval"
});

const timeout = window.setTimeout;
window.setTimeout = new Proxy(timeout, {
  apply(target, thisArg, args) {
    const [callback, delay, ...rest] = args;
    return Reflect.apply(target, thisArg, [callback, (delay ?? 0) * 0.01, ...rest]);
  },
});

Object.defineProperty(window.setTimeout, 'name', {
    value: "setTimeout"
});

const _Worker = window.Worker;

function WorkerMock(scriptURL, options) {
  const fakeWorkerInstance = {
    onmessage: null,
    onerror: null,
    postMessage: function (messageData) {
      const originalSelfPostMessage =
        typeof self !== "undefined" ? self.postMessage : undefined;

      self.postMessage = (replyData) => {
        if (fakeWorkerInstance.onmessage) {
          fakeWorkerInstance.onmessage({ data: replyData });
        }
      };
      const fakeEvent = { data: messageData };

      try {
        if (typeof self.onmessage === "function") {
          self.onmessage(fakeEvent);
        }
      } catch (err) {
        if (fakeWorkerInstance.onerror) fakeWorkerInstance.onerror(err);
      } finally {
        if (originalSelfPostMessage) self.postMessage = originalSelfPostMessage;
      }
    },

    terminate: function () {/* do nothing */ },
  };

  if (typeof scriptURL === "string" && scriptURL.startsWith("blob:")) {

    ['POST', 'GET'].forEach(method => {
        const xhr = new XHR();
        xhr.open(method, scriptURL);
        xhr.send();
    })

    const workerScriptText = xhr.responseText;

    try {
      new Function(workerScriptText)();
    } catch (_) { /* do nothing */ }
  }

  return fakeWorkerInstance;
}

WorkerMock.prototype = _Worker.prototype;

window.Worker = new Proxy(WorkerMock, {});
Object.defineProperty(window.Worker, 'name', {
    value: "Worker"
});

