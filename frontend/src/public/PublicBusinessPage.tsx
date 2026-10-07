import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import { businessTypeLabel } from '../business/businessType'
import { formatServiceDuration, formatServicePrice } from '../business/services/presentation'
import { Button } from '../ui/Button'
import { UnsavedChangesGuardProvider } from '../ui/UnsavedChangesGuard'
import {
  fetchPublicBusinessProfile,
  type PublicBusinessProfile,
  type PublicProfileResult,
} from './api'
import { BookingJourney } from './booking/BookingJourney'
import { newJourneyId, pushJourneyEntry } from './booking/useJourneyHistory'
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
  return (
    <UnsavedChangesGuardProvider>
      <PublicBusinessContent slug={slug} />
    </UnsavedChangesGuardProvider>
  )
}

// The guest booking journey of the open page: only a random history label. It holds no personal data;
// the journey's own state does, and it is keyed by this object.
type OpenJourney = { id: string }

function PublicBusinessContent({ slug }: { slug: string }) {
  const [journey, setJourney] = useState<OpenJourney | null>(null)
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
    if (view && !journey) headingRef.current?.focus()
  }, [view, journey])

  // Each step of the journey adds a history entry holding only a step marker (ADR-0026). The entries are
  // pushed here, in the event handler, so a development double-mount can never push them twice.
  const openJourney = () => {
    const id = newJourneyId()
    pushJourneyEntry(id, 1)
    setJourney({ id })
  }
  const closeJourney = useCallback(() => setJourney(null), [])
  // The Business turned out to be unavailable while booking: the page shows the unavailable answer.
  const businessUnavailable = useCallback(() => {
    setJourney(null)
    setLoaded({ key, outcome: { kind: 'unavailable' } })
  }, [key])

  return (
    <div className="public-page">
      <header className="public-topbar">
        <div className="public-topbar-inner">
          <span className="public-wordmark">{SITE_NAME}</span>
        </div>
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
        {view && view.outcome !== 'failed' && view.outcome.kind === 'profile' && journey && (
          <BookingJourney
            key={`${slug}:${journey.id}`}
            slug={slug}
            businessName={view.outcome.profile.displayName}
            businessPhone={view.outcome.profile.phone}
            services={view.outcome.profile.services}
            journey={journey.id}
            onClose={closeJourney}
            onBusinessUnavailable={businessUnavailable}
          />
        )}
        {view && view.outcome !== 'failed' && view.outcome.kind === 'profile' && !journey && (
          <PublicProfile
            profile={view.outcome.profile}
            headingRef={headingRef}
            onBook={openJourney}
          />
        )}
      </main>
    </div>
  )
}

function PhoneIcon() {
  return (
    <svg className="public-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25c1.1.37 2.3.57 3.6.57a1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z" />
    </svg>
  )
}

function PinIcon() {
  return (
    <svg className="public-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M12 2a7 7 0 0 0-7 7c0 5.25 7 13 7 13s7-7.75 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z" />
    </svg>
  )
}

function PublicProfile({
  profile,
  headingRef,
  onBook,
}: {
  profile: PublicBusinessProfile
  headingRef: RefObject<HTMLHeadingElement | null>
  onBook: () => void
}) {
  const phone = profile.phone?.trim() ?? ''
  const dialable = phone === '' ? null : dialableNumber(phone)
  const lines = addressLines(profile.address)

  return (
    <div className="public-profile">
      <section className="public-hero">
        <div className="public-hero-identity">
          <span className="public-type-chip">{businessTypeLabel(profile.businessType)}</span>
          <h1 ref={headingRef} tabIndex={-1}>
            {profile.displayName}
          </h1>
          {profile.description && <p className="public-description">{profile.description}</p>}
        </div>

        {(phone !== '' || lines.length > 0) && (
          <ul className="public-contacts">
            {phone !== '' && (
              <li>
                <PhoneIcon />
                <span className="visually-hidden">Телефон: </span>
                {dialable ? (
                  <a className="text-link public-phone" href={`tel:${dialable}`}>
                    {phone}
                  </a>
                ) : (
                  <span>{phone}</span>
                )}
              </li>
            )}
            {lines.length > 0 && (
              <li>
                <PinIcon />
                <span className="visually-hidden">Адрес: </span>
                <span className="public-address">
                  {lines.map((line) => (
                    <span key={line} className="public-address-line">
                      {line}
                    </span>
                  ))}
                </span>
              </li>
            )}
          </ul>
        )}

        {profile.services.length > 0 && (
          <Button type="button" className="public-hero-action" onClick={onBook}>
            Запази час
          </Button>
        )}
      </section>

      <section className="public-services-section" aria-labelledby="public-services-heading">
        <h2 id="public-services-heading">Услуги</h2>
        {profile.services.length === 0 ? (
          <p className="public-empty">В момента няма налични услуги за онлайн записване.</p>
        ) : (
          <ul className="public-services">
            {profile.services.map((service) => (
              <li key={service.id} className="public-service">
                <div className="public-service-main">
                  <h3>{service.name}</h3>
                  {service.description && (
                    <p className="public-description">{service.description}</p>
                  )}
                </div>
                <dl className="public-service-facts">
                  <div>
                    <dt>Продължителност</dt>
                    <dd>{formatServiceDuration(service.durationMinutes)}</dd>
                  </div>
                  <div className="public-service-price">
                    <dt>Цена</dt>
                    <dd>{formatServicePrice(service.price)}</dd>
                  </div>
                </dl>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
