package it.spese.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import it.spese.app.ocr.MlKitTextRecognizer
import it.spese.domain.repository.TextRecognizer

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds abstract fun textRecognizer(impl: MlKitTextRecognizer): TextRecognizer
}
