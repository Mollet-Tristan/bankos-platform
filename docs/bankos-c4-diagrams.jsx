import { useState } from "react";

const COLORS = {
  bg: "#0a0e1a",
  surface: "#111827",
  surfaceAlt: "#1a2235",
  border: "#1e2d45",
  accent: "#3b82f6",
  accentGlow: "#60a5fa",
  green: "#10b981",
  orange: "#f59e0b",
  red: "#ef4444",
  purple: "#8b5cf6",
  cyan: "#06b6d4",
  textPrimary: "#e2e8f0",
  textSecondary: "#94a3b8",
  textMuted: "#475569",
  rest: "#3b82f6",
  event: "#10b981",
  grpc: "#8b5cf6",
};

const Badge = ({ color, children }) => (
  <span style={{
    background: color + "22",
    border: `1px solid ${color}55`,
    color: color,
    borderRadius: 4,
    padding: "2px 8px",
    fontSize: 11,
    fontFamily: "monospace",
    fontWeight: 600,
    letterSpacing: "0.05em",
  }}>{children}</span>
);

const Tag = ({ color, children }) => (
  <span style={{
    background: color + "18",
    border: `1px solid ${color}40`,
    color: color,
    borderRadius: 3,
    padding: "1px 6px",
    fontSize: 10,
    fontFamily: "monospace",
  }}>{children}</span>
);

const Arrow = ({ type, label }) => {
  const c = type === "REST" ? COLORS.rest : type === "EVENT" ? COLORS.event : type === "gRPC" ? COLORS.grpc : COLORS.textMuted;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 6, margin: "4px 0" }}>
      <div style={{ height: 1, width: 24, background: c, position: "relative" }}>
        <div style={{
          position: "absolute", right: -4, top: -3,
          width: 0, height: 0,
          borderLeft: `6px solid ${c}`,
          borderTop: "3px solid transparent",
          borderBottom: "3px solid transparent",
        }} />
      </div>
      <Tag color={c}>{type}</Tag>
      {label && <span style={{ color: COLORS.textMuted, fontSize: 11 }}>{label}</span>}
    </div>
  );
};

// ─── LEVEL 1: CONTEXT ───────────────────────────────────────────────────────

const ContextDiagram = () => {
  const [hovered, setHovered] = useState(null);

  const actors = [
    { id: "retail", x: 50, y: 10, label: "Client Retail", icon: "👤", desc: "Particulier utilisant l'app mobile ou web", color: COLORS.cyan },
    { id: "backoffice", x: 10, y: 50, label: "Opérateur Back-Office", icon: "🏦", desc: "Agent bancaire gérant les comptes", color: COLORS.orange },
    { id: "admin", x: 90, y: 50, label: "Administrateur", icon: "⚙️", desc: "Gestion de la plateforme et des droits", color: COLORS.red },
  ];

  const externals = [
    { id: "swift", x: 10, y: 85, label: "SWIFT Network", icon: "🌐", desc: "Virements internationaux", color: COLORS.textMuted },
    { id: "idp", x: 50, y: 88, label: "Identity Provider", icon: "🔐", desc: "Keycloak / OAuth2 / OIDC", color: COLORS.textMuted },
    { id: "notif", x: 90, y: 85, label: "Email / SMS Gateway", icon: "📨", desc: "Prestataire notifications externes", color: COLORS.textMuted },
  ];

  return (
    <div style={{ position: "relative", width: "100%", paddingTop: "68%", userSelect: "none" }}>
      <svg style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }} viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet">
        {/* BankOS system box */}
        <rect x="28" y="28" width="44" height="32" rx="3" fill="#1a2235" stroke={COLORS.accent} strokeWidth="0.5" />
        <text x="50" y="36" textAnchor="middle" fill={COLORS.accentGlow} fontSize="3.5" fontWeight="bold" fontFamily="monospace">BankOS Platform</text>
        <text x="50" y="41" textAnchor="middle" fill={COLORS.textSecondary} fontSize="2.2">[Software System]</text>
        <text x="50" y="46" textAnchor="middle" fill={COLORS.textMuted} fontSize="2" >Plateforme bancaire</text>
        <text x="50" y="49.5" textAnchor="middle" fill={COLORS.textMuted} fontSize="2">de démonstration</text>

        {/* Connections actors → system */}
        {[{x:50,y:10},{x:10,y:50},{x:90,y:50}].map((a, i) => {
          const tx = 50, ty = 44;
          const ex = a.x, ey = a.y + 8;
          return <line key={i} x1={ex} y1={ey} x2={
            i===0?50:i===1?28:72
          } y2={i===0?28:44} stroke={COLORS.border} strokeWidth="0.4" strokeDasharray="1.5,1" />;
        })}

        {/* Connections system → externals */}
        {[{x:10,y:85},{x:50,y:88},{x:90,y:85}].map((e, i) => (
          <line key={i} x1={i===0?32:i===1?50:68} y1={60}
            x2={e.x} y2={e.y}
            stroke={COLORS.border} strokeWidth="0.4" strokeDasharray="1.5,1" />
        ))}
      </svg>

      {/* Actor boxes */}
      {actors.map(a => (
        <div key={a.id}
          onMouseEnter={() => setHovered(a.id)}
          onMouseLeave={() => setHovered(null)}
          style={{
            position: "absolute",
            left: `${a.x}%`, top: `${a.y}%`,
            transform: "translate(-50%, -50%)",
            background: hovered === a.id ? COLORS.surfaceAlt : COLORS.surface,
            border: `1px solid ${a.color}55`,
            borderRadius: 8,
            padding: "8px 12px",
            textAlign: "center",
            cursor: "default",
            transition: "all 0.2s",
            minWidth: 110,
            boxShadow: hovered === a.id ? `0 0 16px ${a.color}33` : "none",
          }}>
          <div style={{ fontSize: 20 }}>{a.icon}</div>
          <div style={{ color: a.color, fontSize: 12, fontWeight: 600 }}>{a.label}</div>
          {hovered === a.id && <div style={{ color: COLORS.textMuted, fontSize: 10, marginTop: 4 }}>{a.desc}</div>}
        </div>
      ))}

      {/* External systems */}
      {externals.map(e => (
        <div key={e.id}
          onMouseEnter={() => setHovered(e.id)}
          onMouseLeave={() => setHovered(null)}
          style={{
            position: "absolute",
            left: `${e.x}%`, top: `${e.y}%`,
            transform: "translate(-50%, -50%)",
            background: "#0d1117",
            border: `1px solid ${COLORS.border}`,
            borderRadius: 6,
            padding: "6px 10px",
            textAlign: "center",
            cursor: "default",
            transition: "all 0.2s",
            minWidth: 100,
          }}>
          <div style={{ fontSize: 16 }}>{e.icon}</div>
          <div style={{ color: COLORS.textSecondary, fontSize: 11 }}>{e.label}</div>
          {hovered === e.id && <div style={{ color: COLORS.textMuted, fontSize: 10, marginTop: 3 }}>{e.desc}</div>}
        </div>
      ))}
    </div>
  );
};

// ─── LEVEL 2: CONTAINER ─────────────────────────────────────────────────────

const containers = [
  {
    id: "gateway",
    label: "API Gateway",
    tech: "Spring Cloud Gateway",
    color: COLORS.cyan,
    icon: "🔀",
    desc: "Point d'entrée unique. Rate limiting, routing, auth filter.",
    adr: "ADR-001",
    adrNote: "Centralise la sécurité en périphérie. Évite de dupliquer l'auth dans chaque service.",
  },
  {
    id: "auth",
    label: "Auth Service",
    tech: "Spring Boot + Keycloak",
    color: COLORS.orange,
    icon: "🔐",
    desc: "OAuth2 / OIDC. Émission et validation des JWT. Gestion des rôles.",
    adr: "ADR-002",
    adrNote: "Keycloak comme IdP externe. Délègue la complexité SSO plutôt que de la réimplémenter.",
  },
  {
    id: "account",
    label: "Account Service",
    tech: "Kotlin + Spring Boot",
    color: COLORS.accent,
    icon: "🏦",
    desc: "Domaine principal. CRUD comptes, soldes, DDD Aggregates.",
    adr: "ADR-003",
    adrNote: "REST vers gateway : réponse synchrone requise pour UX. Kafka vers notifications : découplage.",
  },
  {
    id: "transaction",
    label: "Transaction Service",
    tech: "Kotlin + Kafka Producer",
    color: COLORS.purple,
    icon: "💸",
    desc: "Enregistre les mouvements. Publie des événements TransactionCreated.",
    adr: "ADR-004",
    adrNote: "Event-driven : audit trail naturel, replay possible, scalabilité indépendante.",
  },
  {
    id: "notification",
    label: "Notification Service",
    tech: "Kotlin + Kafka Consumer",
    color: COLORS.green,
    icon: "📬",
    desc: "Consomme les events. Envoie emails/SMS via gateway externe.",
    adr: "ADR-004",
    adrNote: "Consommateur découplé : peut tomber sans impacter le flux transactionnel.",
  },
  {
    id: "reporting",
    label: "Reporting CLI",
    tech: "Rust",
    color: COLORS.red,
    icon: "📊",
    desc: "Analyse de données, statistiques, progression. CPU-bound sans GC.",
    adr: "ADR-005",
    adrNote: "Rust justifié : traitement intensif, pas de latence GC, embarquable en WASM futur.",
  },
];

const connections = [
  { from: "gateway", to: "auth", type: "REST", label: "validate token" },
  { from: "gateway", to: "account", type: "REST", label: "proxy" },
  { from: "gateway", to: "transaction", type: "REST", label: "proxy" },
  { from: "account", to: "transaction", type: "REST", label: "create tx" },
  { from: "transaction", to: "notification", type: "EVENT", label: "TransactionCreated" },
  { from: "reporting", to: "account", type: "REST", label: "read stats" },
];

const POSITIONS = {
  gateway:      { x: 50, y: 8 },
  auth:         { x: 15, y: 35 },
  account:      { x: 50, y: 38 },
  transaction:  { x: 82, y: 38 },
  notification: { x: 82, y: 72 },
  reporting:    { x: 15, y: 72 },
};

const ContainerDiagram = () => {
  const [selected, setSelected] = useState(null);
  const sel = containers.find(c => c.id === selected);

  const getCenter = (id) => ({ x: POSITIONS[id].x, y: POSITIONS[id].y + 7 });

  return (
    <div style={{ display: "flex", gap: 16, height: 500 }}>
      {/* Diagram */}
      <div style={{ flex: 1, position: "relative" }}>
        {/* Kafka bus */}
        <div style={{
          position: "absolute",
          left: "55%", top: "54%",
          transform: "translate(-50%, -50%)",
          width: 180, height: 32,
          background: COLORS.green + "11",
          border: `1px dashed ${COLORS.green}55`,
          borderRadius: 6,
          display: "flex", alignItems: "center", justifyContent: "center",
          fontSize: 11, color: COLORS.green, fontFamily: "monospace",
          zIndex: 1,
        }}>
          ⚡ Kafka Message Bus
        </div>

        <svg style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }} viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet">
          {connections.map((conn, i) => {
            const f = getCenter(conn.from);
            const t = getCenter(conn.to);
            const c = conn.type === "REST" ? COLORS.rest : COLORS.event;
            const mx = (f.x + t.x) / 2;
            const my = (f.y + t.y) / 2;
            return (
              <g key={i}>
                <line x1={f.x} y1={f.y} x2={t.x} y2={t.y}
                  stroke={c} strokeWidth="0.35"
                  strokeDasharray={conn.type === "EVENT" ? "2,1" : "none"}
                  opacity={selected && selected !== conn.from && selected !== conn.to ? 0.15 : 0.7}
                />
                <rect x={mx - 8} y={my - 2.5} width={16} height={5} fill={COLORS.bg} rx={1} />
                <text x={mx} y={my + 1.5} textAnchor="middle" fill={c} fontSize="2" fontFamily="monospace" opacity={0.85}>
                  {conn.label}
                </text>
              </g>
            );
          })}
        </svg>

        {containers.map(c => {
          const pos = POSITIONS[c.id];
          return (
            <div key={c.id}
              onClick={() => setSelected(selected === c.id ? null : c.id)}
              style={{
                position: "absolute",
                left: `${pos.x}%`, top: `${pos.y}%`,
                transform: "translate(-50%, -50%)",
                background: selected === c.id ? COLORS.surfaceAlt : COLORS.surface,
                border: `1px solid ${selected === c.id ? c.color : c.color + "55"}`,
                borderRadius: 8,
                padding: "8px 10px",
                textAlign: "center",
                cursor: "pointer",
                transition: "all 0.2s",
                minWidth: 110,
                boxShadow: selected === c.id ? `0 0 20px ${c.color}44` : "none",
                zIndex: 2,
              }}>
              <div style={{ fontSize: 18 }}>{c.icon}</div>
              <div style={{ color: c.color, fontSize: 11, fontWeight: 700 }}>{c.label}</div>
              <div style={{ color: COLORS.textMuted, fontSize: 9, marginTop: 2 }}>{c.tech}</div>
            </div>
          );
        })}
      </div>

      {/* Detail panel */}
      <div style={{
        width: 240,
        background: COLORS.surfaceAlt,
        border: `1px solid ${COLORS.border}`,
        borderRadius: 10,
        padding: 16,
        display: "flex", flexDirection: "column", gap: 12,
        overflowY: "auto",
      }}>
        {sel ? (
          <>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span style={{ fontSize: 24 }}>{sel.icon}</span>
              <div>
                <div style={{ color: sel.color, fontWeight: 700, fontSize: 14 }}>{sel.label}</div>
                <Tag color={sel.color}>{sel.tech}</Tag>
              </div>
            </div>
            <p style={{ color: COLORS.textSecondary, fontSize: 12, margin: 0, lineHeight: 1.6 }}>{sel.desc}</p>
            <div style={{ borderTop: `1px solid ${COLORS.border}`, paddingTop: 12 }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
                <Badge color={COLORS.orange}>{sel.adr}</Badge>
                <span style={{ color: COLORS.textMuted, fontSize: 11 }}>Décision clé</span>
              </div>
              <p style={{ color: COLORS.textSecondary, fontSize: 11, margin: 0, lineHeight: 1.6, fontStyle: "italic" }}>"{sel.adrNote}"</p>
            </div>
            <div style={{ borderTop: `1px solid ${COLORS.border}`, paddingTop: 12 }}>
              <div style={{ color: COLORS.textMuted, fontSize: 11, marginBottom: 6 }}>Connexions</div>
              {connections.filter(c => c.from === sel.id || c.to === sel.id).map((c, i) => (
                <div key={i} style={{ marginBottom: 4 }}>
                  <div style={{ color: COLORS.textSecondary, fontSize: 11, marginBottom: 2 }}>
                    {c.from === sel.id ? `→ ${c.to}` : `← ${c.from}`}
                  </div>
                  <Arrow type={c.type} label={c.label} />
                </div>
              ))}
            </div>
          </>
        ) : (
          <div style={{ color: COLORS.textMuted, fontSize: 12, textAlign: "center", marginTop: 40 }}>
            <div style={{ fontSize: 32, marginBottom: 12 }}>👆</div>
            Clique sur un container pour voir ses détails et décisions d'architecture
          </div>
        )}
      </div>
    </div>
  );
};

// ─── LEVEL 3: COMPONENT (Account Service) ────────────────────────────────────

const components = [
  { id: "ctrl", label: "AccountController", type: "REST Controller", color: COLORS.cyan, icon: "🔌", x: 50, y: 8, desc: "Expose /accounts, /accounts/{id}/balance. Valide les DTOs entrants, délègue au service." },
  { id: "svc", label: "AccountService", type: "Application Service", color: COLORS.accent, icon: "⚙️", x: 50, y: 38, desc: "Orchestration du domaine. Gère les cas d'usage : créer compte, débiter, créditer. Publie des events internes." },
  { id: "domain", label: "Account (Aggregate)", type: "Domain Model", color: COLORS.purple, icon: "🏛️", x: 25, y: 65, desc: "Aggregate DDD. Invariants métier : solde >= 0, état ACTIVE/FROZEN. Émet AccountDebited, AccountCredited." },
  { id: "repo", label: "AccountRepository", type: "Repository (Port)", color: COLORS.green, icon: "🗄️", x: 75, y: 65, desc: "Interface (port). Implémentée par JPA Adapter. Permet de swapper l'infra sans toucher au domaine." },
  { id: "jpa", label: "JPA Adapter", type: "Infrastructure", color: COLORS.textSecondary, icon: "🔧", x: 75, y: 88, desc: "Implémente AccountRepository. Mappe Account → AccountEntity JPA. PostgreSQL comme storage." },
  { id: "kafka", label: "KafkaEventPublisher", type: "Infrastructure", color: COLORS.orange, icon: "📤", x: 25, y: 88, desc: "Publie AccountDebited/AccountCredited vers Kafka. Pattern Outbox optionnel pour garantir at-least-once." },
];

const compConnections = [
  { from: "ctrl", to: "svc", type: "calls" },
  { from: "svc", to: "domain", type: "uses" },
  { from: "svc", to: "repo", type: "port" },
  { from: "repo", to: "jpa", type: "impl" },
  { from: "svc", to: "kafka", type: "publishes" },
];

const ComponentDiagram = () => {
  const [selected, setSelected] = useState(null);
  const sel = components.find(c => c.id === selected);

  return (
    <div style={{ display: "flex", gap: 16, height: 520 }}>
      <div style={{ flex: 1, position: "relative" }}>
        {/* Hexagonal arch zones */}
        <div style={{
          position: "absolute", left: "10%", top: "50%",
          width: "80%", height: "48%",
          border: `1px dashed ${COLORS.textMuted}33`,
          borderRadius: 8,
          display: "flex", alignItems: "flex-start", justifyContent: "flex-end",
          padding: 6, boxSizing: "border-box",
          pointerEvents: "none",
        }}>
          <span style={{ color: COLORS.textMuted, fontSize: 10, fontFamily: "monospace" }}>Infrastructure Layer</span>
        </div>
        <div style={{
          position: "absolute", left: "15%", top: "27%",
          width: "70%", height: "44%",
          border: `1px dashed ${COLORS.purple}33`,
          borderRadius: 8,
          display: "flex", alignItems: "flex-start", justifyContent: "flex-end",
          padding: 6, boxSizing: "border-box",
          pointerEvents: "none",
        }}>
          <span style={{ color: COLORS.purple + "88", fontSize: 10, fontFamily: "monospace" }}>Domain Layer</span>
        </div>

        <svg style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }} viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet">
          {compConnections.map((conn, i) => {
            const f = components.find(c => c.id === conn.from);
            const t = components.find(c => c.id === conn.to);
            const isImpl = conn.type === "impl";
            return (
              <g key={i}>
                <line
                  x1={f.x} y1={f.y + 7}
                  x2={t.x} y2={t.y}
                  stroke={isImpl ? COLORS.green + "88" : COLORS.border}
                  strokeWidth="0.4"
                  strokeDasharray={isImpl ? "2,1" : "none"}
                  opacity={selected && selected !== conn.from && selected !== conn.to ? 0.1 : 1}
                />
              </g>
            );
          })}
        </svg>

        {components.map(c => (
          <div key={c.id}
            onClick={() => setSelected(selected === c.id ? null : c.id)}
            style={{
              position: "absolute",
              left: `${c.x}%`, top: `${c.y}%`,
              transform: "translate(-50%, -50%)",
              background: selected === c.id ? COLORS.surfaceAlt : COLORS.surface,
              border: `1px solid ${selected === c.id ? c.color : c.color + "44"}`,
              borderRadius: 8,
              padding: "7px 10px",
              textAlign: "center",
              cursor: "pointer",
              transition: "all 0.2s",
              minWidth: 115,
              boxShadow: selected === c.id ? `0 0 16px ${c.color}44` : "none",
              zIndex: 2,
            }}>
            <div style={{ fontSize: 16 }}>{c.icon}</div>
            <div style={{ color: c.color, fontSize: 11, fontWeight: 700 }}>{c.label}</div>
            <div style={{ color: COLORS.textMuted, fontSize: 9, marginTop: 1 }}>{c.type}</div>
          </div>
        ))}
      </div>

      {/* Detail panel */}
      <div style={{
        width: 240,
        background: COLORS.surfaceAlt,
        border: `1px solid ${COLORS.border}`,
        borderRadius: 10,
        padding: 16,
        overflowY: "auto",
      }}>
        {sel ? (
          <>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
              <span style={{ fontSize: 22 }}>{sel.icon}</span>
              <div>
                <div style={{ color: sel.color, fontWeight: 700, fontSize: 13 }}>{sel.label}</div>
                <Tag color={sel.color}>{sel.type}</Tag>
              </div>
            </div>
            <p style={{ color: COLORS.textSecondary, fontSize: 12, margin: 0, lineHeight: 1.6 }}>{sel.desc}</p>
          </>
        ) : (
          <div>
            <div style={{ color: COLORS.accentGlow, fontWeight: 700, marginBottom: 12, fontSize: 13 }}>Account Service — Architecture interne</div>
            <p style={{ color: COLORS.textSecondary, fontSize: 12, lineHeight: 1.6 }}>
              Basé sur <strong style={{ color: COLORS.textPrimary }}>Hexagonal Architecture</strong> (Ports & Adapters).
            </p>
            <p style={{ color: COLORS.textSecondary, fontSize: 12, lineHeight: 1.6 }}>
              Le domaine est isolé de l'infra : swapper JPA pour R2DBC ou MongoDB ne touche pas aux règles métier.
            </p>
            <div style={{ borderTop: `1px solid ${COLORS.border}`, paddingTop: 12, marginTop: 8 }}>
              <div style={{ color: COLORS.textMuted, fontSize: 11, marginBottom: 8 }}>Légende</div>
              <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                {[
                  { color: COLORS.cyan, label: "Entry Point (Controller)" },
                  { color: COLORS.accent, label: "Application Service" },
                  { color: COLORS.purple, label: "Domain Aggregate" },
                  { color: COLORS.green, label: "Port (interface)" },
                  { color: COLORS.textSecondary, label: "Adapter Infrastructure" },
                ].map((item, i) => (
                  <div key={i} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <div style={{ width: 10, height: 10, borderRadius: 2, background: item.color + "33", border: `1px solid ${item.color}66` }} />
                    <span style={{ color: COLORS.textSecondary, fontSize: 11 }}>{item.label}</span>
                  </div>
                ))}
              </div>
            </div>
            <div style={{ marginTop: 16, color: COLORS.textMuted, fontSize: 11, textAlign: "center" }}>
              👆 Clique sur un composant
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

// ─── LEGEND / ADR SUMMARY ────────────────────────────────────────────────────

const ADRPanel = () => {
  const adrs = [
    { id: "ADR-001", title: "API Gateway comme point d'entrée unique", decision: "REST", why: "Centralise auth, rate limiting et routing. Évite la duplication de logique transversale dans chaque service." },
    { id: "ADR-002", title: "Keycloak comme Identity Provider externe", decision: "Délégation", why: "OAuth2/OIDC est un standard éprouvé. Ne pas réimplémenter SSO, session management, MFA." },
    { id: "ADR-003", title: "REST synchrone entre Gateway ↔ Account/Transaction", decision: "REST", why: "L'UX nécessite une réponse immédiate. Le couplage temporel est acceptable ici." },
    { id: "ADR-004", title: "Kafka asynchrone entre Transaction ↔ Notification", decision: "EVENT", why: "La notification n'est pas critique pour le flux principal. Découplage, scalabilité indépendante, audit trail naturel." },
    { id: "ADR-005", title: "Rust pour le Reporting CLI", decision: "Rust", why: "Traitement CPU-bound sans overhead GC. Embarquable en WASM pour futur client web. Démontre polyglottisme." },
  ];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      {adrs.map(adr => (
        <div key={adr.id} style={{
          background: COLORS.surface,
          border: `1px solid ${COLORS.border}`,
          borderRadius: 8,
          padding: "12px 16px",
          display: "flex", gap: 16, alignItems: "flex-start",
        }}>
          <Badge color={COLORS.orange}>{adr.id}</Badge>
          <div style={{ flex: 1 }}>
            <div style={{ color: COLORS.textPrimary, fontSize: 13, fontWeight: 600, marginBottom: 4 }}>{adr.title}</div>
            <div style={{ color: COLORS.textSecondary, fontSize: 12, lineHeight: 1.5 }}>{adr.why}</div>
          </div>
          <Badge color={adr.decision === "REST" ? COLORS.rest : adr.decision === "EVENT" ? COLORS.event : COLORS.purple}>
            {adr.decision}
          </Badge>
        </div>
      ))}
    </div>
  );
};

// ─── MAIN APP ────────────────────────────────────────────────────────────────

const TABS = [
  { id: "c1", label: "L1 — Context", subtitle: "Qui utilise le système ?" },
  { id: "c2", label: "L2 — Containers", subtitle: "Quels services ? Cliquer pour explorer." },
  { id: "c3", label: "L3 — Components", subtitle: "Intérieur de Account Service" },
  { id: "adr", label: "ADRs", subtitle: "Décisions d'architecture documentées" },
];

export default function App() {
  const [tab, setTab] = useState("c1");
  const current = TABS.find(t => t.id === tab);

  return (
    <div style={{
      background: COLORS.bg,
      minHeight: "100vh",
      fontFamily: "'IBM Plex Sans', 'Segoe UI', sans-serif",
      color: COLORS.textPrimary,
      padding: "24px",
      boxSizing: "border-box",
    }}>
      {/* Header */}
      <div style={{ marginBottom: 24 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 12, marginBottom: 4 }}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, color: COLORS.accentGlow, letterSpacing: "-0.02em" }}>
            BankOS Platform
          </h1>
          <Badge color={COLORS.green}>C4 Model</Badge>
          <Badge color={COLORS.purple}>Portfolio SA</Badge>
        </div>
        <p style={{ margin: 0, color: COLORS.textMuted, fontSize: 13 }}>
          Architecture de démonstration — Tri-couche synchrone / asynchrone · DDD · Hexagonal · OAuth2
        </p>
      </div>

      {/* Tabs */}
      <div style={{ display: "flex", gap: 4, marginBottom: 20, borderBottom: `1px solid ${COLORS.border}`, paddingBottom: 0 }}>
        {TABS.map(t => (
          <button key={t.id} onClick={() => setTab(t.id)} style={{
            background: "none", border: "none", cursor: "pointer",
            padding: "8px 16px",
            color: tab === t.id ? COLORS.accentGlow : COLORS.textMuted,
            borderBottom: tab === t.id ? `2px solid ${COLORS.accentGlow}` : "2px solid transparent",
            fontSize: 13, fontWeight: tab === t.id ? 700 : 400,
            transition: "all 0.2s",
            marginBottom: -1,
          }}>{t.label}</button>
        ))}
      </div>

      {/* Subtitle */}
      <p style={{ color: COLORS.textSecondary, fontSize: 12, marginBottom: 16, marginTop: 0, fontStyle: "italic" }}>
        {current.subtitle}
      </p>

      {/* Content */}
      <div style={{
        background: COLORS.surface,
        border: `1px solid ${COLORS.border}`,
        borderRadius: 12,
        padding: 20,
      }}>
        {tab === "c1" && <ContextDiagram />}
        {tab === "c2" && <ContainerDiagram />}
        {tab === "c3" && <ComponentDiagram />}
        {tab === "adr" && <ADRPanel />}
      </div>

      {/* Legend REST vs EVENT */}
      {(tab === "c2") && (
        <div style={{ display: "flex", gap: 16, marginTop: 16 }}>
          {[
            { color: COLORS.rest, type: "REST", desc: "Synchrone — réponse immédiate requise", dash: false },
            { color: COLORS.event, type: "EVENT (Kafka)", desc: "Asynchrone — découplage, scalabilité", dash: true },
          ].map(item => (
            <div key={item.type} style={{
              display: "flex", alignItems: "center", gap: 10,
              background: COLORS.surface, border: `1px solid ${COLORS.border}`,
              borderRadius: 6, padding: "8px 14px", flex: 1,
            }}>
              <svg width="32" height="12">
                <line x1="0" y1="6" x2="32" y2="6" stroke={item.color} strokeWidth="1.5"
                  strokeDasharray={item.dash ? "4,2" : "none"} />
                <polygon points="28,3 32,6 28,9" fill={item.color} />
              </svg>
              <div>
                <div style={{ color: item.color, fontSize: 12, fontWeight: 600 }}>{item.type}</div>
                <div style={{ color: COLORS.textMuted, fontSize: 11 }}>{item.desc}</div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
