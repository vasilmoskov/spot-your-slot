import { useEffect, useRef, useState, type RefObject } from 'react'
import { businessTypeLabel } from '../business/businessType'
import { formatServiceDuration, formatServicePrice } from '../business/services/presentation'
import { Button } from '../ui/Button'
import {
  fetchPublicBusinessProfile,
  type PublicBusinessProfile,
  type PublicProfileResult,
} from './api'
import { addressLines, dialableNumber, truncateAtWord } from './presentation'
import { usePageMetadata, type PageMetadata } from './usePageMetadata'

const SITE_NAME = 'SpotYourSlot'
const UNAVAILABLE_TITLE = `Страницата не е налична – ${SITE_NAME}`

type Loaded = { key: string; outcome: PublicProfileResult | 'failed' }

function metadataFor(view: Loaded | null): PageMetadata | null {
  if (!view) return null
  if (view.outcome === 'failed' || view.outcome.kind === 'unavailable') {
    return { title: UNAVAILABLE_TITLE, noindex: true }
  }
  const { profile } = view.outcome
  return {
    title: `${profile.displayName} – ${SITE_NAME}`,
    description: profile.description
      ? truncateAtWord(profile.description)
      : `Информация и услуги на ${profile.displayName}.`,
    canonicalUrl: `${window.location.origin}/${profile.slug}`,
  }
}

/**
 * The unauthenticated public page of one Business (issue #17). It fetches
 * only `GET /api/public/businesses/{slug}`; a response that belongs to a
 * superseded slug or attempt is never rendered, and nothing from a previous
 * Business stays on screen while another one loads.
 */
export function PublicBusinessPage({ slug }: { slug: string }) {
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const key = `${slug}#${attempt}`

  useEffect(() => {
    const controller = new AbortController()
    fetchPublicBusinessProfile(slug, controller.signal).then(
      (outcome) => {
        if (!controller.signal.aborted) setLoaded({ key, outcome })
      },
      () => {
        if (!controller.signal.aborted) setLoaded({ key, outcome: 'failed' })
      },
    )
    return () => controller.abort()
  }, [slug, key])

  // Data that belongs to another slug or attempt counts as still loading.
  const view = loaded?.key === key ? loaded : null

  usePageMetadata(metadataFor(view))

  useEffect(() => {
    if (view) headingRef.current?.focus()
  }, [view])

  return (
    <div className="public-page">
      <header className="public-topbar">
        <span className="public-wordmark">{SITE_NAME}</span>
      </header>
      <main className="public-main">
        {!view && (
          <p role="status" className="public-status">
            Зареждане на страницата…
          </p>
        )}
        {view?.outcome === 'failed' && (
          <section className="public-state" aria-labelledby="public-state-heading">
            <h1 id="public-state-heading" ref={headingRef} tabIndex={-1}>
              Страницата не може да бъде заредена.
            </h1>
            <p>Проверете връзката си и опитайте отново.</p>
            <Button type="button" variant="secondary" onClick={() => setAttempt((n) => n + 1)}>
              Опитайте отново
            </Button>
          </section>
        )}
        {view && view.outcome !== 'failed' && view.outcome.kind === 'unavailable' && (
          <section className="public-state" aria-labelledby="public-state-heading">
            <h1 id="public-state-heading" ref={headingRef} tabIndex={-1}>
              Страницата не е налична
            </h1>
            <p>Проверете адреса или опитайте по-късно.</p>
          </section>
        )}
        {view && view.outcome !== 'failed' && view.outcome.kind === 'profile' && (
          <PublicProfile profile={view.outcome.profile} headingRef={headingRef} />
        )}
      </main>
    </div>
  )
}

function PublicProfile({
  profile,
  headingRef,
}: {
  profile: PublicBusinessProfile
  headingRef: RefObject<HTMLHeadingElement | null>
}) {
  const phone = profile.phone?.trim() ?? ''
  const dialable = phone === '' ? null : dialableNumber(phone)
  const lines = addressLines(profile.address)
  const hasContacts = phone !== '' || lines.length > 0

  return (
    <div className="public-profile">
      <header className="public-profile-header">
        <h1 ref={headingRef} tabIndex={-1}>
          {profile.displayName}
        </h1>
        <span className="public-type-chip">{businessTypeLabel(profile.businessType)}</span>
        {profile.description && <p className="public-description">{profile.description}</p>}
      </header>

      {hasContacts && (
        <section className="public-section" aria-labelledby="public-contacts-heading">
          <h2 id="public-contacts-heading">Контакти</h2>
          <dl className="public-facts">
            {phone !== '' && (
              <div>
                <dt>Телефон</dt>
                <dd>
                  {dialable ? (
                    <a className="text-link public-phone" href={`tel:${dialable}`}>
                      {phone}
                    </a>
                  ) : (
                    phone
                  )}
                </dd>
              </div>
            )}
            {lines.length > 0 && (
              <div>
                <dt>Адрес</dt>
                <dd>
                  {lines.map((line) => (
                    <span key={line} className="public-address-line">
                      {line}
                    </span>
                  ))}
                </dd>
              </div>
            )}
          </dl>
        </section>
      )}

      <section className="public-section" aria-labelledby="public-services-heading">
        <h2 id="public-services-heading">Услуги</h2>
        {profile.services.length === 0 ? (
          <p className="public-empty">В момента няма налични услуги за онлайн записване.</p>
        ) : (
          <ul className="public-services">
            {profile.services.map((service, index) => (
              <li key={`${index}-${service.name}`} className="public-service">
                <h3>{service.name}</h3>
                {service.description && (
                  <p className="public-description">{service.description}</p>
                )}
                <dl className="public-service-facts">
                  <div>
                    <dt>Продължителност</dt>
                    <dd>{formatServiceDuration(service.durationMinutes)}</dd>
                  </div>
                  <div>
                    <dt>Цена</dt>
                    <dd>{formatServicePrice(service.price)}</dd>
                  </div>
                </dl>
              </li>
            ))}
          </ul>
        )}
      </section>

      <p className="public-notice">Онлайн запазването на час все още не е налично.</p>
    </div>
  )
}
