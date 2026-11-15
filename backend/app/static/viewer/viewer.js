/*
 * Visualizador PDF mínimo sobre PDF.js (build "legacy"), para a WebView do JavaFX.
 * Fase 0: rolagem contínua, zoom e navegação por página, renderização sob demanda.
 * Escrito em JS conservador (sem módulos) porque a WebView usa WebKit.
 *
 * Parâmetros da URL: ?doc=<id>&token=<token>
 * Ponte opcional com o Java: window.javaBridge.onStatus(msg) / onPageChanged(page, total)
 */
(function () {
  "use strict";

  var params = new URLSearchParams(window.location.search);
  var docId = params.get("doc");
  var token = params.get("token");
  // ?chrome=0 esconde a barra da página: o aplicativo usa controles nativos.
  if (params.get("chrome") === "0") document.body.classList.add("nochrome");

  var MIN_ZOOM = 0.1, MAX_ZOOM = 4.0, ZOOM_STEP = 0.1;
  var SCROLLBAR_ALLOWANCE = 28; // barra de rolagem + folga, para a página nunca ficar cortada

  var scroller = document.getElementById("scroller");
  var pagesEl = document.getElementById("pages");
  var pageInput = document.getElementById("page-input");
  var pageCountEl = document.getElementById("page-count");
  var zoomLabel = document.getElementById("zoom-label");
  var fitButton = document.getElementById("fit");
  var messageEl = document.getElementById("message");

  var pdf = null;
  var zoom = 1.0;
  var baseSize = { width: 612, height: 792 }; // estimativa até a página real ser renderizada
  var pageEls = [];
  var rendered = {};
  var renderToken = {};
  var observer = null;
  var currentPage = 1;
  var firstRenderReported = false;
  var pageSizes = [];
  var fitMode = true;      // ajusta o zoom à largura do painel automaticamente
  var resizeTimer = null;

  function bridge() { return window.javaBridge || null; }

  function status(msg) {
    var b = bridge();
    if (b && b.onStatus) { try { b.onStatus(String(msg)); } catch (e) { /* ignora */ } }
  }

  function showMessage(text) {
    messageEl.textContent = text || "";
    messageEl.classList.toggle("visible", !!text);
  }

  function fail(err) {
    var text = "Erro ao abrir o PDF: " + (err && err.message ? err.message : err);
    showMessage(text);
    status(text);
  }

  function pageDims(i) {
    var el = pageEls[i - 1];
    var w = el._width || baseSize.width;
    var h = el._height || baseSize.height;
    return { width: w * zoom, height: h * zoom };
  }

  function sizePlaceholder(i) {
    var d = pageDims(i);
    var el = pageEls[i - 1];
    el.style.width = d.width + "px";
    el.style.height = d.height + "px";
  }

  function renderPage(i) {
    if (rendered[i]) return;
    var token = (renderToken[i] = (renderToken[i] || 0) + 1);
    rendered[i] = "pending";
    pdf.getPage(i).then(function (page) {
      if (renderToken[i] !== token) { page.cleanup(); return; } // saiu da tela / zoom mudou
      var base = page.getViewport({ scale: 1 });
      var el = pageEls[i - 1];
      el._width = base.width;
      el._height = base.height;
      sizePlaceholder(i);

      var dpr = window.devicePixelRatio || 1;
      var viewport = page.getViewport({ scale: zoom * dpr });
      var canvas = document.createElement("canvas");
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      canvas.style.width = Math.floor(viewport.width / dpr) + "px";
      canvas.style.height = Math.floor(viewport.height / dpr) + "px";

      return page.render({ canvasContext: canvas.getContext("2d"), viewport: viewport }).promise.then(function () {
        page.cleanup();
        if (renderToken[i] !== token) return;
        el.innerHTML = "";
        el.appendChild(canvas);
        rendered[i] = "done";
        if (!firstRenderReported) {
          firstRenderReported = true;
          status("Primeira página renderizada (página " + i + ", " + canvas.width + "x" + canvas.height + " px)");
        }
      });
    }).catch(function (err) {
      if (renderToken[i] === token) rendered[i] = null;
      status("Falha ao renderizar a página " + i + ": " + (err && err.message ? err.message : err));
    });
  }

  // Libera o canvas de páginas que saíram da tela (limita o uso de memória em PDFs grandes).
  function releasePage(i) {
    if (!rendered[i]) return;
    renderToken[i] = (renderToken[i] || 0) + 1; // invalida renderização em andamento
    rendered[i] = null;
    pageEls[i - 1].innerHTML = "";
  }

  var MAX_PREFETCH_PAGES = 400;

  // Lê o tamanho real de cada página para o espaço reservado ficar certo (evita "pulos" na rolagem).
  function loadPageSizes(doc) {
    var sizes = [];
    if (doc.numPages > MAX_PREFETCH_PAGES) {
      // PDF enorme: só a primeira página serve de referência para o ajuste de largura.
      return doc.getPage(1).then(function (page) {
        var v = page.getViewport({ scale: 1 });
        sizes[1] = { width: v.width, height: v.height };
        page.cleanup();
        return sizes;
      });
    }
    var batch = 25;
    function next(from) {
      if (from > doc.numPages) return Promise.resolve(sizes);
      var jobs = [];
      for (var n = from; n < from + batch && n <= doc.numPages; n++) {
        jobs.push(doc.getPage(n).then((function (num) {
          return function (page) {
            var v = page.getViewport({ scale: 1 });
            sizes[num] = { width: v.width, height: v.height };
            page.cleanup();
          };
        })(n)));
      }
      return Promise.all(jobs).then(function () { return next(from + batch); });
    }
    return next(1);
  }

  function buildPages() {
    pagesEl.innerHTML = "";
    pageEls = [];
    rendered = {};
    if (observer) observer.disconnect();
    observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        var n = Number(entry.target.dataset.page);
        if (entry.isIntersecting) renderPage(n); else releasePage(n);
      });
    }, { root: scroller, rootMargin: "800px 0px" });

    for (var i = 1; i <= pdf.numPages; i++) {
      var el = document.createElement("div");
      el.className = "page";
      el.dataset.page = String(i);
      if (pageSizes[i]) { el._width = pageSizes[i].width; el._height = pageSizes[i].height; }
      pagesEl.appendChild(el);
      pageEls.push(el);
      sizePlaceholder(i);
      observer.observe(el);
    }
  }

  function updateCurrentPage() {
    if (!pdf) return;
    var mid = scroller.scrollTop + scroller.clientHeight / 3;
    var best = 1;
    for (var i = 0; i < pageEls.length; i++) {
      if (pageEls[i].offsetTop - pagesEl.offsetTop <= mid) best = i + 1; else break;
    }
    if (best !== currentPage) {
      currentPage = best;
      pageInput.value = String(best);
      var b = bridge();
      if (b && b.onPageChanged) { try { b.onPageChanged(best, pdf.numPages); } catch (e) { /* ignora */ } }
    }
  }

  function goToPage(n) {
    if (!pdf) return;
    n = Math.max(1, Math.min(pdf.numPages, Math.floor(n) || 1));
    var el = pageEls[n - 1];
    scroller.scrollTop = el.offsetTop - pagesEl.offsetTop;
    currentPage = n;
    pageInput.value = String(n);
    notifyPage();
  }

  function clampZoom(value) {
    return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
  }

  // Avisa o Java (controles nativos) do zoom atual e do modo "ajustar".
  function notifyZoom() {
    var b = bridge();
    if (b && b.onZoomChanged) { try { b.onZoomChanged(Math.round(zoom * 100), fitMode); } catch (e) { /* ignora */ } }
  }

  function notifyPage() {
    var b = bridge();
    if (pdf && b && b.onPageChanged) { try { b.onPageChanged(currentPage, pdf.numPages); } catch (e) { /* ignora */ } }
  }

  // Aplica um zoom (sem mexer no modo "ajustar"), mantendo a página atual.
  function applyZoom(value) {
    if (!pdf) return;
    var next = clampZoom(value);
    if (Math.abs(next - zoom) < 0.001) { notifyZoom(); return; }
    var page = currentPage;
    zoom = next;
    zoomLabel.textContent = Math.round(zoom * 100) + "%";
    buildPages();
    goToPage(page);
    notifyZoom();
  }

  // Zoom manual (botões +/-): sai do modo "ajustar à largura".
  function setZoom(value) {
    setFit(false);
    applyZoom(value);
  }

  // Largura da página mais larga conhecida (em pontos PDF, zoom 1).
  function widestPage() {
    var widest = 0;
    for (var i = 0; i < pageSizes.length; i++) {
      if (pageSizes[i] && pageSizes[i].width > widest) widest = pageSizes[i].width;
    }
    for (var j = 0; j < pageEls.length; j++) {
      if (pageEls[j]._width > widest) widest = pageEls[j]._width;
    }
    return widest || baseSize.width;
  }

  // Zoom em que a página mais larga cabe inteira no painel (arredondado para baixo: nunca corta).
  function fitZoom() {
    var available = scroller.offsetWidth - SCROLLBAR_ALLOWANCE;
    if (!(available > 0)) return zoom;
    return clampZoom(Math.floor(available / widestPage() * 100) / 100);
  }

  function setFit(on) {
    fitMode = on;
    fitButton.classList.toggle("active", on);
    if (on) applyZoom(fitZoom()); else notifyZoom();
  }

  // Quando o painel muda de tamanho (divisor, janela), o zoom acompanha para não cortar o PDF.
  window.addEventListener("resize", function () {
    if (!fitMode || !pdf) return;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { applyZoom(fitZoom()); }, 120);
  });

  document.getElementById("prev").addEventListener("click", function () { goToPage(currentPage - 1); });
  document.getElementById("next").addEventListener("click", function () { goToPage(currentPage + 1); });
  document.getElementById("zoom-in").addEventListener("click", function () { setZoom(zoom + ZOOM_STEP); });
  document.getElementById("zoom-out").addEventListener("click", function () { setZoom(zoom - ZOOM_STEP); });
  fitButton.addEventListener("click", function () { setFit(!fitMode); });
  pageInput.addEventListener("change", function () { goToPage(Number(pageInput.value)); });
  scroller.addEventListener("scroll", updateCurrentPage);

  // API para o Java (ponte JavaFX <-> JS).
  window.viewer = {
    goToPage: goToPage,
    prevPage: function () { goToPage(currentPage - 1); },
    nextPage: function () { goToPage(currentPage + 1); },
    zoomIn: function () { setZoom(zoom + ZOOM_STEP); },
    zoomOut: function () { setZoom(zoom - ZOOM_STEP); },
    // Reenvia página e zoom ao Java (usado depois que a ponte é instalada na WebView).
    republish: function () { notifyPage(); notifyZoom(); },
    setZoom: setZoom,
    setFit: setFit,
    getZoom: function () { return zoom; },
    isFit: function () { return fitMode; },
    getPage: function () { return currentPage; }
  };

  if (!docId || !token) {
    showMessage("Nenhum documento carregado.");
    return;
  }
  if (typeof pdfjsLib === "undefined") {
    fail("PDF.js não foi carregado");
    return;
  }

  pdfjsLib.GlobalWorkerOptions.workerSrc = "pdfjs/pdf.worker.min.js";
  var url = "/documents/" + encodeURIComponent(docId) + "/file?token=" + encodeURIComponent(token);

  pdfjsLib.getDocument({ url: url }).promise.then(function (doc) {
    pdf = doc;
    pageCountEl.textContent = "/ " + doc.numPages;
    pageInput.max = String(doc.numPages);
    return loadPageSizes(doc).then(function (sizes) {
      pageSizes = sizes;
      if (fitMode) {
        zoom = fitZoom();
        zoomLabel.textContent = Math.round(zoom * 100) + "%";
      }
      buildPages();
      // O painel pode ter mudado de tamanho enquanto o PDF carregava (o evento resize foi ignorado).
      if (fitMode) applyZoom(fitZoom());
      status("PDF carregado: " + doc.numPages + " páginas (zoom " + Math.round(zoom * 100) + "%, painel " + scroller.offsetWidth + " px)");
      notifyPage();
      notifyZoom();
    });
  }).catch(fail);
})();
