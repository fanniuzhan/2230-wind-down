using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;

namespace CustomWindDown.App;

internal static class Ui
{
    public static readonly SolidColorBrush Ink = Brush("#182F38");
    public static readonly SolidColorBrush Muted = Brush("#64777D");
    public static readonly SolidColorBrush Accent = Brush("#226C60");
    public static readonly SolidColorBrush Canvas = Brush("#F4F5F0");
    public static SolidColorBrush Brush(string hex) => (SolidColorBrush)new BrushConverter().ConvertFromString(hex)!;

    public static TextBlock Text(string text, double size = 14, Brush? color = null)
        => new() { Text = text, FontSize = size, Foreground = color ?? Ink,
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 8) };

    public static Button Button(string text, bool primary = false)
    {
        var button = new Button
        {
            Content = text, FontSize = 14, Padding = new Thickness(18, 10, 18, 10),
            Background = primary ? Accent : Brushes.White,
            Foreground = primary ? Brushes.White : Ink,
            BorderBrush = primary ? Accent : Brush("#D6DFD8"),
            BorderThickness = new Thickness(1), Cursor = System.Windows.Input.Cursors.Hand,
            Margin = new Thickness(0, 0, 10, 0), MinHeight = 40
        };
        var border = new FrameworkElementFactory(typeof(Border));
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(9));
        border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
        border.SetValue(Border.BorderBrushProperty, new TemplateBindingExtension(Control.BorderBrushProperty));
        border.SetValue(Border.BorderThicknessProperty, new TemplateBindingExtension(Control.BorderThicknessProperty));
        border.SetValue(Border.PaddingProperty, new TemplateBindingExtension(Control.PaddingProperty));
        var content = new FrameworkElementFactory(typeof(ContentPresenter));
        content.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        content.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);
        border.AppendChild(content);
        var template = new ControlTemplate(typeof(Button)) { VisualTree = border };
        var hover = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
        hover.Setters.Add(new Setter(UIElement.OpacityProperty, .85));
        template.Triggers.Add(hover);
        var disabled = new Trigger { Property = UIElement.IsEnabledProperty, Value = false };
        disabled.Setters.Add(new Setter(UIElement.OpacityProperty, .45));
        template.Triggers.Add(disabled);
        button.Template = template;
        AutomationProperties.SetName(button, text);
        return button;
    }

    public static Border Card(UIElement child, string color = "#FFFFFF", Thickness? padding = null)
        => new() { Background = Brush(color), CornerRadius = new CornerRadius(18),
            Padding = padding ?? new Thickness(26), Child = child };
}
