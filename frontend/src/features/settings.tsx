"use client";
import { Layout, LayoutContent } from "@astryxdesign/core/Layout";
import { Selector } from "@astryxdesign/core/Selector";
import { useOrganization } from "@/components/workspace";
import { useAppearance } from "@/components/providers";
import { PageHeader } from "@/components/ui";
export function WorkspaceSettings() {
  const { organization, session } = useOrganization();
  const { mode, setMode } = useAppearance();
  return (
    <Layout height="auto" padding={6}>
      <LayoutContent padding={6}>
        <div className="page settings-page">
          <PageHeader
            title="Settings"
            description="Your organization, identity, and personal preferences."
          />
          <section className="settings-section">
            <div>
              <h2>Organization</h2>
              <p className="muted">Membership is managed by the backend.</p>
            </div>
            <dl>
              <dt>Name</dt>
              <dd>{organization.name}</dd>
              <dt>Your role</dt>
              <dd>{organization.role}</dd>
              <dt>Organization ID</dt>
              <dd className="mono">{organization.id}</dd>
            </dl>
          </section>
          <section className="settings-section">
            <div>
              <h2>Account</h2>
              <p className="muted">Identity provided by Keycloak.</p>
            </div>
            <dl>
              <dt>Username</dt>
              <dd>{session.identity.username ?? "Not supplied"}</dd>
              <dt>Subject</dt>
              <dd className="mono">{session.identity.subject}</dd>
              <dt>Issuer</dt>
              <dd className="mono">{session.identity.issuer}</dd>
            </dl>
          </section>
          <section className="settings-section">
            <div>
              <h2>Appearance</h2>
              <p className="muted">Stored on this device.</p>
            </div>
            <Selector
              label="Color theme"
              value={mode}
              options={[
                { value: "system", label: "Follow system" },
                { value: "light", label: "Light" },
                { value: "dark", label: "Dark" },
              ]}
              onChange={(v) => setMode(v as typeof mode)}
              width="100%"
            />
          </section>
          <section className="settings-section">
            <div>
              <h2>About Traceback</h2>
              <p className="muted">Local engineering workspace.</p>
            </div>
            <div>
              <p>Next.js · Meta Astryx Neutral 0.7.0</p>
              <p className="muted">
                AI uses the backend's configured local model. Availability is
                reported when you search or send a message; no model-health API
                exists.
              </p>
              <p className="muted">
                Conversations are private to their creator. Answers show
                validated sources and become unavailable when evidence changes.
              </p>
            </div>
          </section>
        </div>
      </LayoutContent>
    </Layout>
  );
}
