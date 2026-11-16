"""Gera PDFs de teste para o autoteste do Electron (usa o PyMuPDF do backend).

Uso: python make_test_pdfs.py <pasta-de-saida>
Imprime um JSON {nome: caminho}.
  texto.pdf      24 páginas A4 com texto; a página 7 é paisagem (mais larga) para testar o ajuste à largura
  outro.pdf      8 páginas com texto
  escaneado.pdf  3 páginas só com desenhos (sem texto): deve virar "no_text"
"""

import json
import sys
import warnings
from pathlib import Path

warnings.filterwarnings("ignore")
try:
    import pymupdf  # type: ignore
except ImportError:  # versões antigas
    import fitz as pymupdf  # type: ignore

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)

LOREM = [
    "A ordenacao por comparacao nao pode ser melhor que O(n log n) no pior caso.",
    "O mergesort divide a lista, ordena cada metade e intercala os resultados.",
    "O quicksort escolhe um pivo e particiona os elementos em menores e maiores.",
    "Estruturas de dados determinam o custo das operacoes de busca e insercao.",
]


def texto(path: Path, pages: int, wide_page: int | None, prefix: str) -> None:
    doc = pymupdf.open()
    for n in range(1, pages + 1):
        w, h = (842, 595) if n == wide_page else (595, 842)
        page = doc.new_page(width=w, height=h)
        page.insert_text((56, 72), f"{prefix} - Capitulo {n}", fontsize=20)
        page.insert_text((56, 100), f"MARCADOR-PAGINA-{prefix}-{n}", fontsize=11)
        y = 140
        for i in range(24):
            page.insert_text((56, y), LOREM[(n + i) % len(LOREM)], fontsize=11)
            y += 22
            if y > h - 60:
                break
    doc.save(path)
    doc.close()


def escaneado(path: Path) -> None:
    doc = pymupdf.open()
    for _ in range(3):
        page = doc.new_page(width=595, height=842)
        for k in range(12):
            page.draw_rect(pymupdf.Rect(56, 80 + k * 50, 540, 110 + k * 50), color=(0.3, 0.3, 0.3), fill=(0.85, 0.85, 0.85))
    doc.save(path)
    doc.close()


texto(out / "texto.pdf", 24, 7, "A")
texto(out / "outro.pdf", 8, None, "B")
escaneado(out / "escaneado.pdf")
print(json.dumps({p.stem: str(p) for p in sorted(out.glob("*.pdf"))}))
