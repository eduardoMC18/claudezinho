import { useEffect, useState } from 'react';
import { GlassProvider } from './glass/GlassProvider';
import { useChat } from './chat/useChat';
import { Placeholder } from './screens/Placeholder';
import { Reader } from './screens/Reader';
import { Settings } from './screens/Settings';
import { Sidebar, type ScreenId } from './ui/Sidebar';
import { runSelfTest } from './selftest';

export function App() {
  const [screen, setScreen] = useState<ScreenId>('reader');
  const chat = useChat(); // fora das telas: uma resposta em andamento continua ao trocar de tela

  useEffect(() => {
    void window.estudo.info().then((info) => { if (info.selfTest) void runSelfTest(); });
  }, []);

  return (
    <GlassProvider>
      <div className="app">
        <Sidebar screen={screen} onSelect={setScreen} />
        <div className="main">
          {screen === 'reader' && <Reader chat={chat} />}
          {screen === 'exercises' && <Placeholder title="Criar Exercícios" what="Questões objetivas e subjetivas geradas a partir dos PDFs." />}
          {screen === 'settings' && <Settings />}
        </div>
      </div>
    </GlassProvider>
  );
}
