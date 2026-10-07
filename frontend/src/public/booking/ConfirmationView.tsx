import { formatServiceDuration, formatServicePrice } from '../../business/services/presentation'
import { Button } from '../../ui/Button'
import { dialableNumber } from '../presentation'
import type { ConfirmedBooking } from './api'
import { formatInstantDate, formatTimeDistinct } from './dates'

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
 * The result of a successful response. Every fact comes from the server's answer (the Service and
 * StaffMember snapshots, the instants in the returned timezone, the EUR price); nothing is taken from
 * the form. A cancelled reservation (a replay of an attempt whose Appointment was cancelled later) is
 * shown as cancelled and never as a confirmed booking.
 */
export function ConfirmationView({
  booking,
  replayed,
  businessPhone,
  onClose,
}: {
  booking: ConfirmedBooking
  replayed: boolean
  businessPhone: string | null
  onClose: () => void
}) {
  const cancelled = booking.status === 'CANCELLED'
  const { timezone } = booking
  return (
    <div className="booking-confirmation">
      {cancelled ? (
        <div className="status-message status-error" role="status">
          <p>Тази резервация е отменена и часът не е запазен.</p>
        </div>
      ) : (
        <div className="status-message status-success" role="status">
          <p>
            {replayed
              ? 'Тази резервация вече беше потвърдена.'
              : 'Часът е запазен. Резервацията е потвърдена.'}
          </p>
        </div>
      )}
      <dl className="booking-facts">
        <div>
          <dt>Статус</dt>
          <dd>{cancelled ? 'Отменена' : 'Потвърдена'}</dd>
        </div>
        <div>
          <dt>Референтен номер</dt>
          <dd className="booking-reference">{booking.reference}</dd>
        </div>
        <div>
          <dt>Услуга</dt>
          <dd>{booking.service.name}</dd>
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
            {formatTimeDistinct(booking.start, timezone)} – {formatTimeDistinct(booking.end, timezone)}
          </dd>
        </div>
        <div>
          <dt>Продължителност</dt>
          <dd>{formatServiceDuration(booking.service.durationMinutes)}</dd>
        </div>
        <div>
          <dt>Цена</dt>
          <dd>{formatServicePrice(booking.service.price)}</dd>
        </div>
        <div>
          <dt>Часова зона</dt>
          <dd>{timezone}</dd>
        </div>
      </dl>
      {cancelled ? (
        <ContactBusiness
          phone={businessPhone}
          lead="Ако не очаквате това, свържете се с бизнеса."
        />
      ) : (
        <>
          <p>Не изпращаме потвърждение по имейл или SMS. Запишете си тези данни.</p>
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
