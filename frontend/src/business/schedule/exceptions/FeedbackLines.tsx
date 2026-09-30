// One sentence per paragraph: a message whose lines are joined by a newline is
// rendered as separate paragraphs, never as one run-on line.
export function FeedbackLines({ text }: { text: string }) {
  return (
    <>
      {text.split('\n').map((line) => (
        <p key={line}>{line}</p>
      ))}
    </>
  )
}
