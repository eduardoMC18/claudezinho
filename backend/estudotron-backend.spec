# -*- mode: python ; coding: utf-8 -*-
# Empacota o backend em uma pasta (onedir): dist-pkg/estudotron-backend/estudotron-backend.exe
#
#   .venv\Scripts\python.exe -m PyInstaller --noconfirm --distpath dist-pkg --workpath build-pkg estudotron-backend.spec
#
# O código acha seus dados por Path(__file__).parent, então os dados vão para os MESMOS subdiretórios (app/...).
from PyInstaller.utils.hooks import collect_submodules

hidden = (
    collect_submodules("uvicorn")              # loops/protocolos escolhidos em tempo de execução
    + collect_submodules("keyring.backends")   # o cofre do Windows é carregado por entry point
    + ["win32ctypes.core", "win32ctypes.pywin32.win32cred", "win32ctypes.pywin32.pywintypes"]
    + collect_submodules("google.genai")
    + collect_submodules("anthropic")
    + collect_submodules("app")                # routers/serviços importados indiretamente
)

a = Analysis(
    ["run_backend.py"],
    pathex=["."],
    binaries=[],
    datas=[
        ("app/infra/migrations", "app/infra/migrations"),   # migrações SQL
        ("app/providers/catalog.json", "app/providers"),    # modelos e preços
        ("app/static", "app/static"),                       # visualizador PDF.js (ainda montado em /viewer)
    ],
    hiddenimports=hidden,
    hookspath=[],
    runtime_hooks=[],
    excludes=["tkinter", "pytest", "tests", "IPython", "matplotlib", "numpy.tests"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="estudotron-backend",
    debug=False,
    strip=False,
    upx=False,        # UPX aumenta falsos positivos de antivírus
    console=True,     # precisa de stdin/stdout (a casca o inicia sem janela e usa o pipe de stdin como vigia)
)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="estudotron-backend")
