import { useEffect, useState } from 'react';
import type { ExRef } from './api';
import { useChat } from './chat/useChat';
import { GlassProvider } from './glass/GlassProvider';
import { Exercises } from './screens/Exercises';
import { Reader } from './screens/Reader';
import type { CiteTarget } from './screens/ChatPanel';
import { Settings } from './screens/Settings';
import { Sidebar, type ScreenId } from './ui/Sidebar';
import { useSidebar } from './ui/useSidebar';
import { runSelfTest } from './selftest';

export function App() {
  const [screen, setScreen] = useState<ScreenId>('reader');
  const [pending, setPending] = useState<CiteTarget | null>(null);
  const chat = useChat(); // fora das telas: uma resposta em andamento continua ao trocar de tela
  const sidebar = useSidebar();

  // a largura da barra anima em CSS; o vidro precisa redesenhar a cada quadro enquanto isso
  useEffect(() => { window.__glass?.engine?.kick(450); }, [sidebar.collapsed]);

  useEffect(() => {
    void window.estudo.info().then((info) => { if (info.selfTest) void runSelfTest(); });
  }, []);

  /** Clique numa referência de outra tela: troca para o Leitor e deixa o PDF na página citada. */
  const openReference = (r: ExRef) => {
    setPending({ slot: r.slot, page: r.page, documentId: r.document_id });
    setScreen('reader');
  };

  return (
    <GlassProvider>
      <div className="app" data-sidebar={sidebar.collapsed ? 'collapsed' : 'expanded'}>
        <Sidebar screen={screen} onSelect={setScreen} collapsed={sidebar.collapsed} onToggle={sidebar.toggle} />
        <div className="main">
          {screen === 'reader' && <Reader chat={chat} pending={pending} onPendingDone={() => setPending(null)} onOpenSettings={() => setScreen('settings')} />}
          {screen === 'exercises' && (
            <Exercises onOpenReader={() => setScreen('reader')} onOpenSettings={() => setScreen('settings')} onOpenReference={openReference} />
          )}
          {screen === 'settings' && <Settings />}
        </div>
      </div>
    </GlassProvider>
  );
}
