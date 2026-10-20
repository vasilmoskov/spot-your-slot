import { formatServiceDuration, formatServicePrice } from '../../business/services/presentation'
import { Button } from '../../ui/Button'
import { dialableNumber } from '../presentation'
import type { ConfirmedBooking } from './api'
import { formatInstantDate, formatVisitRange } from './dates'
import { formatCents } from './money'
import { priceToCents } from './money'

function ContactBusiness({ phone, lead }: { phone: string | null; lead: string }) {
  const text = phone?.trim() ?? ''
  const dialable = text === '' ? null : dialableNumber(text)
  return (
    <p>
      {lead}
      {text !== '' && (
        <>
          {' '}
          Телефон:{' '}
          {dialable ? (
            <a className="text-link public-phone" href={`tel:${dialable}`}>
              {text}
            </a>
          ) : (
            text
          )}
        </>
      )}
    </p>
  )
}

/**
 * The result of a successful response. Every fact comes from the server's answer (the stored Service
 * lines and the server's own totals, the StaffMember snapshot, the instants in the returned Business timezone, the EUR price); nothing is taken
 * from the form. The reference and the timezone stay in the answer but are not shown. A cancelled
 * reservation (a replay of an attempt whose Appointment was cancelled later) is shown as cancelled and
 * never as a confirmed booking. The page makes no claim that a message was sent.
 */
export function ConfirmationView({
  booking,
  businessPhone,
  onClose,
}: {
  booking: ConfirmedBooking
  businessPhone: string | null
  onClose: () => void
}) {
  const cancelled = booking.status === 'CANCELLED'
  const { timezone } = booking
  return (
    <div className="booking-confirmation">
      {cancelled && (
        <div className="status-message status-error" role="status">
          <p>Тази резервация е отменена и часът не е запазен.</p>
        </div>
      )}
      <dl className="booking-facts">
        <div>
          <dt>{booking.services.length > 1 ? 'Услуги' : 'Услуга'}</dt>
          <dd>
            {booking.services.length === 1 ? (
              booking.services[0]!.name
            ) : (
              <ul className="booking-summary-services">
                {booking.services.map((line, index) => (
                  <li key={index}>
                    <span className="booking-summary-strong">{line.name}</span>
                    <span>
                      {formatServicePrice(line.price)} — {formatServiceDuration(line.durationMinutes)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </dd>
        </div>
        <div>
          <dt>Служител</dt>
          <dd>{booking.staff.displayName}</dd>
        </div>
        <div>
          <dt>Дата</dt>
          <dd>{formatInstantDate(booking.start, timezone)}</dd>
        </div>
        <div>
          <dt>Час</dt>
          <dd>
            {formatVisitRange(booking.start, booking.end, timezone)}
          </dd>
        </div>
        <div>
          <dt>Продължителност</dt>
          <dd>{formatServiceDuration(booking.totalDurationMinutes)}</dd>
        </div>
        <div>
          <dt>Цена</dt>
          <dd>{formatCents(priceToCents(booking.totalPrice))}</dd>
        </div>
      </dl>
      {cancelled ? (
        <ContactBusiness
          phone={businessPhone}
          lead="Ако не очаквате това, свържете се с бизнеса."
        />
      ) : (
        <>
          <p>Запазете данните за резервацията.</p>
          <ContactBusiness
            phone={businessPhone}
            lead="За промяна или отмяна се свържете с бизнеса."
          />
        </>
      )}
      <div className="action-group booking-actions">
        <Button type="button" variant="secondary" onClick={onClose}>
          Към страницата на бизнеса
        </Button>
      </div>
    </div>
  )
}
