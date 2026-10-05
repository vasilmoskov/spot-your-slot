import { useId } from 'react'
import { useGuardedBusinessAction } from './useGuardedBusinessAction'
import { Button } from '../ui/Button'
import type { Business } from '../identity/api'

type BusinessSelectionProps = {
  // Only Businesses the user manages (an active owner Membership).
  businesses: readonly Business[]
  activeBusinessId: string | undefined
  busy: boolean
  // Selects the Business and opens its first destination; resolves once finished.
  onManage: (businessId: string) => void
}

// Visible lifecycle wording (ui guide section 9); the technical status is never shown.
const STATUS_PRESENTATION: Record<string, { label: string; tone: 'neutral' | 'success' | 'warning' }> = {
  DRAFT: { label: 'Предстои активиране', tone: 'neutral' },
  ACTIVE: { label: 'Активен', tone: 'success' },
  SUSPENDED: { label: 'Временно спрян', tone: 'warning' },
}

export function BusinessSelection({
  businesses,
  activeBusinessId,
  busy,
  onManage,
}: BusinessSelectionProps) {
  const manage = useGuardedBusinessAction(onManage)

  if (businesses.length === 0) {
    return (
      <div className="platform-content">
        <p className="business-list-state">Нямате бизнеси, които можете да управлявате.</p>
      </div>
    )
  }

  return (
    <div className="platform-content">
      <ul className="business-choice-list" aria-label="Вашите бизнеси">
        {businesses.map((business) => (
          <BusinessChoice
            key={business.id}
            business={business}
            selected={business.id === activeBusinessId}
            busy={busy}
            onShow={manage}
          />
        ))}
      </ul>
    </div>
  )
}

type BusinessChoiceProps = {
  business: Business
  selected: boolean
  busy: boolean
  onShow: (businessId: string) => void
}

// One card per Business, named by its heading so the "Покажи" button needs no longer label: the
// card supplies the context. The checkmark marks the current selection only; the lifecycle badge
// next to the name is a separate fact (a DRAFT or SUSPENDED Business can be the selected one).
function BusinessChoice({ business, selected, busy, onShow }: BusinessChoiceProps) {
  const headingId = useId()
  const status = STATUS_PRESENTATION[business.status]
  return (
    <li>
      <article
        className={selected ? 'business-choice is-selected' : 'business-choice'}
        aria-labelledby={headingId}
        aria-current={selected ? 'true' : undefined}
      >
        <div className="business-choice-identity">
          <div className="business-choice-title">
            <h2 id={headingId} className="business-choice-name">
              {business.displayName}
            </h2>
            {selected && (
              <>
                <span className="business-choice-check" aria-hidden="true">
                  ✓
                </span>
                <span className="visually-hidden">Текущо избран бизнес</span>
              </>
            )}
          </div>
          {status && (
            <div className="business-choice-badges">
              <span className={`status-badge status-badge-${status.tone}`}>{status.label}</span>
            </div>
          )}
        </div>
        <Button
          type="button"
          variant={selected ? 'secondary' : 'primary'}
          disabled={busy}
          onClick={() => onShow(business.id)}
        >
          Покажи
        </Button>
      </article>
    </li>
  )
}
