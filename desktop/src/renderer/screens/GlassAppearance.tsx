import type { ReactNode } from 'react';
import { THICKNESS_MAX, THICKNESS_MIN, usePreferences, type GlassMode, type GlassQuality } from '../glass/preferences';
import { GlassButton } from '../ui/GlassButton';
import { GlassPanel } from '../ui/GlassPanel';

function Segmented<T extends string>({ name, label, value, options, onChange }: {
  name: string; label: string; value: T; options: Array<{ value: T; label: string }>; onChange(v: T): void;
}) {
  return (
    <div className="seg" role="radiogroup" aria-label={label} data-pref={name}>
      {options.map((o) => (
        <button key={o.value} type="button" role="radio" aria-checked={o.value === value} data-value={o.value} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  );
}

function Row({ title, hint, children }: { title: string; hint: string; children: ReactNode }) {
  return (
    <div className="pref-row">
      <div className="pref-text"><b>{title}</b><span>{hint}</span></div>
      <div className="pref-control">{children}</div>
    </div>
  );
}

function Switch({ name, label, checked, onChange }: { name: string; label: string; checked: boolean; onChange(v: boolean): void }) {
  return (
    <label className="switch">
      <input type="checkbox" role="switch" data-pref={name} aria-label={label} checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span className="track" aria-hidden="true"><span className="knob" /></span>
    </label>
  );
}

const QUALITY_NAME: Record<GlassQuality, string> = { low: 'baixa', medium: 'média', high: 'alta' };

/**
 * Aparência do vidro: o material é um parâmetro do usuário (como no GlassCN e no GlassyUI), não um visual fixo.
 * Controles planos e opacos de propósito: os ajustes não gastam superfícies de vidro nem competem com o material.
 */
export function GlassAppearance() {
  const { prefs, quality, detected, setPrefs, reset } = usePreferences();
  const auto = prefs.quality === null;
  const thicknessText = prefs.thickness.toFixed(2).replace('.', ',') + '×';

  return (
    <GlassPanel className="prov appearance" radius={28} bevel={18} data-section="appearance" aria-label="Aparência do vidro">
      <div className="prov-head">
        <h2>Aparência do vidro</h2>
        <GlassButton className="sm" variant="film" radius={17} bevel={12} data-action="reset-glass" onClick={reset}>Restaurar padrões</GlassButton>
      </div>

      <Row title="Modo" hint="Transparente deixa o fundo aparecer; Tingido acrescenta um véu claro e favorece a leitura.">
        <Segmented<GlassMode> name="mode" label="Modo do vidro" value={prefs.mode} onChange={(mode) => setPrefs({ mode })}
          options={[{ value: 'clear', label: 'Transparente' }, { value: 'tinted', label: 'Tingido' }]} />
      </Row>

      <Row title="Espessura" hint={`Quanto o vidro desvia o que está atrás. De ${THICKNESS_MIN.toString().replace('.', ',')}× a ${THICKNESS_MAX}× o normal.`}>
        <div className="range-wrap">
          <input type="range" className="range" data-pref="thickness" aria-label="Espessura do vidro" min={THICKNESS_MIN} max={THICKNESS_MAX} step={0.05}
            value={prefs.thickness} onChange={(e) => setPrefs({ thickness: parseFloat(e.target.value) })} />
          <output className="range-out" data-pref-out="thickness">{thicknessText}</output>
        </div>
      </Row>

      <Row title="Qualidade" hint={`Resolução do fundo refratado e dispersão de cor. Em uso: ${QUALITY_NAME[quality]}${auto ? ` (detectada pelo aparelho)` : ''}.`}>
        <Segmented<'auto' | GlassQuality> name="quality" label="Qualidade do vidro" value={auto ? 'auto' : (prefs.quality as GlassQuality)}
          onChange={(v) => setPrefs({ quality: v === 'auto' ? null : v })}
          options={[{ value: 'auto', label: `Automática (${QUALITY_NAME[detected]})` }, { value: 'low', label: 'Baixa' }, { value: 'medium', label: 'Média' }, { value: 'high', label: 'Alta' }]} />
      </Row>

      <Row title="Reduzir movimento" hint="Fundo parado e botões sem balanço. Segue a configuração do sistema até você escolher.">
        <Switch name="reduceMotion" label="Reduzir movimento" checked={prefs.reduceMotion} onChange={(reduceMotion) => setPrefs({ reduceMotion })} />
      </Row>

      <Row title="Reduzir transparência" hint="Superfícies opacas, sem refração (também economiza a placa de vídeo).">
        <Switch name="reduceTransparency" label="Reduzir transparência" checked={prefs.reduceTransparency} onChange={(reduceTransparency) => setPrefs({ reduceTransparency })} />
      </Row>
    </GlassPanel>
  );
}
