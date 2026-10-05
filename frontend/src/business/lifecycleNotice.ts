import type { AuthenticatedRoute } from '../navigation'

// What the shared SUSPENDED banner says. The wording follows the domain operation of the screen
// instead of one generic sentence, so a screen never describes its records as something else.
const GENERIC_SUSPENDED_NOTICE =
  'Бизнесът е временно спрян. Данните са видими, но конфигурацията не може да бъде променяна.'

const CUSTOMERS_SUSPENDED_NOTICE =
  'Бизнесът е временно спрян. Можете да преглеждате и редактирате клиентите, но не можете да добавяте нови.'

export function suspendedNotice(route: AuthenticatedRoute): string {
  if (route.kind.startsWith('business-customer')) return CUSTOMERS_SUSPENDED_NOTICE
  return GENERIC_SUSPENDED_NOTICE
}
