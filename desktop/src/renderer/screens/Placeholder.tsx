import { GlassPanel } from '../ui/GlassPanel';

/** Telas ainda não migradas do JavaFX. Honesto: não finge funcionalidade. */
export function Placeholder({ title, what }: { title: string; what: string }) {
  return (
    <>
      <header className="head"><h1>{title}</h1></header>
      <div className="screen">
        <GlassPanel className="notice" radius={28} bevel={18}>
          <h2>Em migração</h2>
          <p>{what} Esta tela será construída na próxima etapa; até lá, o app JavaFX continua sendo o oficial.</p>
        </GlassPanel>
      </div>
    </>
  );
}
