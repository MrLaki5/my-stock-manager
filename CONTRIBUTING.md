# Contributing

Thanks for wanting to help with MyStockManager. Bug reports, ideas and pull requests are welcome.

## Before you start

- **Bugs:** open an issue with your phone model, Android version and the steps that cause the bug.
- **Features:** open an issue first so we can agree on the idea before you write the code.
- **Security problems:** don't open a public issue. Contact the maintainer privately through
  [GitHub](https://github.com/MrLaki5) instead.

## Making a change

1. Fork the repository and create a branch from `main`.
2. Build and install the app with `./gradlew installDebug` (see [Build](README.md#build)).
3. Run the unit tests with `./gradlew testDebugUnitTest`. CI runs them on every pull request.
4. Keep each pull request to one change, and match the style of the code around it.
5. Use [Conventional Commits](https://www.conventionalcommits.org/) for commit messages, for
   example `feat: ...`, `fix: ...` or `docs: ...`.

## Licensing your contribution

MyStockManager is source available under the [PolyForm Noncommercial License 1.0.0](LICENSE).
Its author, Milan Lazarević, also sells separate commercial licenses. To keep that possible,
every contribution comes with the terms below.

By submitting a contribution (code, documentation or anything else) through a pull request,
patch or any other means, you agree that:

1. Your contribution is licensed under the PolyForm Noncommercial License 1.0.0, like the rest
   of the project.
2. You also grant Milan Lazarević a perpetual, worldwide, non-exclusive, royalty-free and
   irrevocable license to use, copy, modify, distribute, sublicense and relicense your
   contribution under any terms, including commercial ones.
3. You keep the copyright in your contribution. You can still use your own work in any way you
   like.
4. You have the right to submit it. It is your own work, or you have permission from whoever
   owns it, such as your employer. It contains no code under a license that conflicts with
   these terms.

If you can't agree to these terms, please don't open a pull request. An issue describing the
change is still welcome.
